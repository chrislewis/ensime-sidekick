package com.codecommit
package es

import errorlist.{DefaultErrorSource, ErrorSource}

import org.gjt.sp.jedit
import jedit.{jEdit => JEdit}
import jedit.{Buffer, EBMessage, EBPlugin, TextUtilities, View}
import jedit.msg.ViewUpdate
import jedit.textarea.Selection

import java.awt.{EventQueue, Toolkit}
import java.io.File

import javax.swing.JOptionPane

import scala.io.Source
import scala.util.parsing.input.CharSequenceReader

import client._
import util._

class EnsimePlugin extends EBPlugin {
  override def start() {
  }
  
  override def handleMessage(message: EBMessage) = message match {
    case _ =>
  }
  
  override def stop() {
    EnsimePlugin.stopAll()
  }
}

object EnsimePlugin {
  private var instances = Map[File, Instance]()
  private val lock = new AnyRef
  
  def EnsimeHome = new File(Option(JEdit.getProperty(EnsimeHomeProperty)) getOrElse "/Users/daniel/Local/ensime_2.9.1-0.6.RC3")
  def SbtOpts = Option(JEdit.getProperty(SbtOptsProperty)) getOrElse ""
  
  val EnsimeHomeProperty = "ensime.home"
  val SbtOptsProperty = "ensime.sbt-opts"
  
  def stopAll() {
    lock synchronized {
      instances foreach { case (root, inst) =>
        System.err.println("Stopping ENSIME backend at " + root)
        inst.stop()
      }
      instances = instances.empty
    }
  }
  
  def initProject(view: View) {
    // TODO compensate for slow (network) file systems
    val canonicalPath = new File(view.getBuffer.getPath).getCanonicalPath
    val parents = parentDirs(new File(canonicalPath))
    
    if (parents exists instances.contains) {
      view.getStatus.setMessage("ENSIME: Already initialized!")
    } else {
      val projectPath = parents find { f =>
        val files = f.listFiles
        
        if (files != null)
          files filter (null !=) map { _.getName } contains ".ensime"
        else
          false
      } map { f => new File(f, ".ensime") } map { _.getCanonicalPath } getOrElse canonicalPath
      
      EventQueue.invokeLater(new Runnable {
        def run() {
          for {
            n <- Option(JOptionPane.showInputDialog(view, "ENSIME Project File:", projectPath))
          } Some(new File(n)).filter(_.exists)
              .map(initProjectFile)
              .getOrElse(JOptionPane.showMessageDialog(view, "Specified project file does not exist!", "Error", JOptionPane.ERROR_MESSAGE))
        }
      })
    }
    
    def initProjectFile(projectFile: File) = {
      val src = Source fromFile projectFile
      val optProjectData = Option(SExp.read(new CharSequenceReader(src.mkString)))
      
      if (!optProjectData.isDefined) {
        JOptionPane.showMessageDialog(view, "Project file is invalid.  Make sure it consists of a single s-expression.  (no comments!)", "Error", JOptionPane.ERROR_MESSAGE)
      }
      
      for (projectData @ SExpList(data) <- optProjectData) {
        val optSubprojectItems = projectData.toKeywordMap.get(SExp.key(":sbt-subprojects")) collect { case SExpList(items) => Vector(items.toList: _*) }
        val optSubprojects = optSubprojectItems map { _ collect { case xs: SExpList => xs.toKeywordMap } map { _(SExp.key(":name")) } collect { case StringAtom(str) => str } }
        
        val optActiveSubproject = optSubprojects flatMap { subprojects =>
          val dialog = new ui.SubprojectSelectorDialog(view, subprojects)
          dialog.open()
        }
        
        if (!optSubprojects.isDefined || optActiveSubproject.isDefined) {
          val parentDir = projectFile.getParentFile
          val projectData2 = SExpList(
            SExp.key(":root-dir") :: StringAtom(parentDir.getCanonicalPath) ::
            (optActiveSubproject map { str => SExp.key(":sbt-active-subproject") :: StringAtom(str) :: data.toList } getOrElse data.toList))
          
          val home = projectData2.toKeywordMap get SExp.key(":ensime-home") collect { case StringAtom(str) => str } map { new File(_) } filter { _.exists } getOrElse EnsimeHome
          
          view.getStatus.setMessage("ENSIME: Starting server...")
          
          val inst = new Instance(parentDir, home) { self =>
            def fatalServerError(msg: String) {
              EventQueue.invokeLater(new Runnable {
                def run() {
                  JOptionPane.showMessageDialog(view, "ENSIME: Fatal Error!  " + msg, "Error", JOptionPane.ERROR_MESSAGE)
                }
              })
              
              lock synchronized {
                for ((root, `self`) <- instances) {
                  instances -= root
                }
              }
              
              self.stop()
            }
          }
          
          inst.start("SBT_OPTS" -> SbtOpts)
          inst.Ensime.initProject(projectData2) { (projectName, sourceRoots) =>
            if (sourceRoots.isEmpty) {
              EventQueue.invokeLater(new Runnable {
                def run() {
                  JOptionPane.showMessageDialog(view, "ENSIME failed to start!  Could not detect source roots.", "Error", JOptionPane.ERROR_MESSAGE)
                }
              })
              inst.stop()
            } else {
              lock synchronized {
                sourceRoots foreach { root => instances += (root -> inst) }
              }
            }
          }
        }
      }
    }
  }
  
  def stopProject(view: View) {
    for (inst <- instanceForBuffer(view.getBuffer)) {
      lock synchronized {
        for ((root, `inst`) <- instances) {
          instances -= root
        }
      }
      inst.stop()
      view.getStatus.setMessage("ENSIME: Server instance killed")
    }
  }
  
  def autoComplete(buffer: Buffer, caret: Int, prefix: String)(callback: List[EnsimeProtocol.CompletionResult] => Unit) {
    for (inst <- instanceForBuffer(buffer)) {
      val file = if (buffer.isDirty) {
        buffer.autosave()
        buffer.getAutosaveFile
      } else {
        new File(buffer.getPath)
      }
      
      inst.Ensime.typeCompletion(file.getCanonicalPath, caret, prefix)(callback)
    }
  }
  
  def determineType(view: View) {
    val buffer = view.getBuffer
    
    for (inst <- instanceForBuffer(buffer)) {
      val filename = if (buffer.isDirty) {
        buffer.autosave()
        buffer.getAutosaveFile
      } else {
        new File(buffer.getPath)
      }
      
      inst.Ensime.typeAtPoint(filename.getCanonicalPath, view.getTextArea.getCaretPosition) { t =>
        view.getStatus.setMessage(t.friendlyName)
      }
    }
  }
  
  def typecheckFile(buffer: Buffer) {
    for (inst <- instanceForBuffer(buffer)) {
      if (!buffer.isDirty) {
        inst.Ensime.typecheckFile(new File(buffer.getPath).getCanonicalPath)
      }
    }
  }
  
  def jumpToDeclaration(view: View) {
    val buffer = view.getBuffer
    
    for (inst <- instanceForBuffer(buffer)) {
      val filename = if (buffer.isDirty) {
        buffer.autosave()
        buffer.getAutosaveFile
      } else {
        new File(buffer.getPath)
      }
      
      inst.Ensime.symbolAtPoint(filename.getCanonicalPath, view.getTextArea.getCaretPosition) {
        case Some(loc) => {
          EventQueue.invokeLater(new Runnable {
            def run() {
              type Navigator = { def addToHistory() }
              type NavigatorPlugin = { def getNavigator(view: View): Navigator }
              
              val navPlugin = Option(JEdit.getPlugin("ise.plugin.nav.NavigatorPlugin")) map { _.asInstanceOf[NavigatorPlugin] }
              navPlugin foreach { _.getNavigator(view).addToHistory() }
              
              val buffer = JEdit.openFile(view, loc.file)
              val pane = view.goToBuffer(buffer)
              val area = pane.getTextArea
              
              EventQueue.invokeLater(new Runnable {
                def run() {
                  area.setCaretPosition(loc.offset)
                  
                  navPlugin foreach { _.getNavigator(view).addToHistory() }
                }
              })
            }
          })
        }
        
        case None => {
          Toolkit.getDefaultToolkit.beep()
          view.getStatus.setMessage("ENSIME: Could not locate declaration!")
        }
      }
    }
  }
  
  def expandSelection(view: View) {
    val buffer = view.getBuffer
    
    for (inst <- instanceForBuffer(buffer)) {
      val filename = if (buffer.isDirty) {
        buffer.autosave()
        buffer.getAutosaveFile
      } else {
        new File(buffer.getPath)
      }
      
      val area = view.getTextArea
      
      def parseSelection = {
        if (area.getSelectionCount == 1) {
          val select = area.getSelection.head
          Some((select.getStart, select.getEnd))
        } else if (area.getSelectionCount == 0) {
          Some((area.getCaretPosition, area.getCaretPosition))
        } else {
          None
        }
      }
      
      val selection = parseSelection
      
      for ((start, end) <- selection) {
        inst.Ensime.expandSelection(filename.getCanonicalPath, start, end) { (start2, end2) =>
          if (parseSelection == selection) {
            EventQueue.invokeLater(new Runnable {
              def run() {
                area.setCaretPosition(end2)
                area.setSelection(new Selection.Range(start2, end2))
              }
            })
          }
        }
      }
    }
  }
  
  def suggestImports(view: View) {
    val buffer = view.getBuffer
    
    for (inst <- instanceForBuffer(buffer)) {
      val filename = if (buffer.isDirty) {
        buffer.autosave()
        buffer.getAutosaveFile
      } else {
        new File(buffer.getPath)
      }
      
      val area = view.getTextArea
      val point = area.getCaretPosition
      
      val caretLine = area.getCaretLine
      val caretInLine = point - buffer.getLineStartOffset(caretLine)
      val line = buffer.getLineText(caretLine)
      
      val start = TextUtilities.findWordStart(line, caretInLine, "")
      val end = TextUtilities.findWordEnd(line, caretInLine, "")
      
      val word = line.substring(start, end)
      
      inst.Ensime.importSuggestions(filename.getCanonicalPath, point, List(word), 5) { suggestions =>
        def insertImport(suggestion: String) {
          val (insertionPoint, pad) = ImportFinder(suggestion) { i =>
            Option(area getLineText i)
          }
          
          val oldPos = area.getCaretPosition
          val toInsert = "%simport %s\n".format(if (pad) "\n" else "", suggestion)
          
          EventQueue.invokeLater(new Runnable {
            def run() {
              area.setCaretPosition(area.getLineStartOffset(insertionPoint), false)
              area.setSelectedText(toInsert)
              area.setCaretPosition(oldPos + toInsert.length, true)
            }
          })
        }
        
        val editorXY = area.getLocationOnScreen
        val xy = area.offsetToXY(point)
        xy.translate(editorXY.x, editorXY.y)
        
        val popup = new ui.ImportSuggestionsPopup(view, xy, Vector(suggestions: _*))(insertImport)
        popup.show()
      }
    }
  }
  
  def symbolSearch(view: View) {
    val buffer = view.getBuffer
    for (inst <- instanceForBuffer(buffer)) {
      val dialog = new ui.SymbolSearchDialog(view, inst.Ensime.publicSymbolSearch)
      
      for ((file, offset) <- dialog.open() if offset >= 0) {
        EventQueue.invokeLater(new Runnable {
          def run() {
            type Navigator = { def addToHistory() }
            type NavigatorPlugin = { def getNavigator(view: View): Navigator }
            
            val navPlugin = Option(JEdit.getPlugin("ise.plugin.nav.NavigatorPlugin")) map { _.asInstanceOf[NavigatorPlugin] }
            navPlugin foreach { _.getNavigator(view).addToHistory() }
              
            val buffer = JEdit.openFile(view, file)
            val pane = view.goToBuffer(buffer)
            val area = pane.getTextArea
            
            EventQueue.invokeLater(new Runnable {
              def run() {
                area.setCaretPosition(offset)
                
                navPlugin foreach { _.getNavigator(view).addToHistory() }
              }
            })
          }
        })
      }
    }
  }
  
  private def instanceForBuffer(buffer: Buffer) =
    parentDirs(new File(buffer.getPath)) flatMap instances.get headOption
  
  private def parentDirs(base: File): Stream[File] =
    base #:: (Option(base.getParent) map { new File(_) } map parentDirs getOrElse Stream.empty)
}

abstract class Instance(val RootDir: File, val EnsimeHome: File) extends EnsimeProtocolComponent with EnsimeBackendComponent {
  val Handler = new SidekickBackendHandler(new DefaultErrorSource("ENSIME"))
  
  def start(env: (String, String)*) {
    ErrorSource.registerErrorSource(Handler.errorSource)
    Backend.start(env: _*)(handle(Handler))
  }
  
  def stop() {
    Handler.errorSource.clear()
    ErrorSource.unregisterErrorSource(Handler.errorSource)
    Backend.stop()
  }
}

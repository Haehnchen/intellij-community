package com.intellij.ide.actions;

import com.intellij.CommonBundle;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.highlighter.ProjectFileType;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.projectImport.ProjectOpenProcessor;
import com.intellij.projectImport.ProjectOpenProcessorBase;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Icons;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class OpenProjectAction extends AnAction {
  public void actionPerformed(AnActionEvent e) {
    Project project = PlatformDataKeys.PROJECT.getData(e.getDataContext());

    final FileChooserDescriptor descriptor = new FileChooserDescriptor(true, true, false, false, false, false) {
      public boolean isFileSelectable(final VirtualFile file) {
        return isProjectDirectory(file) || isProjectFile(file);
      }

      public Icon getOpenIcon(final VirtualFile virtualFile) {
        if (isProjectDirectory(virtualFile)) return Icons.PROJECT_ICON;
        final Icon icon = getImporterIcon(virtualFile, true);
        if(icon!=null){
          return icon;
        }
        return super.getOpenIcon(virtualFile);
      }
      public Icon getClosedIcon(final VirtualFile virtualFile) {
        if (isProjectDirectory(virtualFile)) return Icons.PROJECT_ICON;
        final Icon icon = getImporterIcon(virtualFile, false);
        if(icon!=null){
          return icon;
        }
        return super.getClosedIcon(virtualFile);
      }

      @Nullable
      public Icon getImporterIcon(final VirtualFile virtualFile, final boolean open) {
        final ProjectOpenProcessor provider = ProjectUtil.getImportProvider(virtualFile);
        if(provider!=null) {
          return provider.getIcon();
        }
        return null;
      }

      public boolean isFileVisible(final VirtualFile file, final boolean showHiddenFiles) {
        return super.isFileVisible(file, showHiddenFiles) && (file.isDirectory() || isProjectFile(file));
      }
    };
    descriptor.setTitle(IdeBundle.message("title.open.project"));
    String [] extensions = new String[]{ProjectFileType.DOT_DEFAULT_EXTENSION};
    final ProjectOpenProcessor[] openProcessors = Extensions.getExtensions(ProjectOpenProcessorBase.EXTENSION_POINT_NAME);
    for (ProjectOpenProcessor openProcessor : openProcessors) {
      final String[] supportedExtensions = ((ProjectOpenProcessorBase)openProcessor).getSupportedExtensions();
      if (supportedExtensions != null) {
        extensions = ArrayUtil.mergeArrays(extensions, supportedExtensions, String.class);
      }
    }
    descriptor.setDescription(IdeBundle.message("filter.project.files", StringUtil.join(extensions, ", ")));
    final VirtualFile[] files = FileChooser.chooseFiles(project, descriptor);

    if (files.length == 0 || files[0] == null) return;

    final Project openedProject = ProjectUtil.openOrImport(files[0].getPath(), project, false);
    if (openedProject == null) {
      Messages.showErrorDialog(project, IdeBundle.message("fail.open.project.message", files[0].getPath()), CommonBundle.message("title.error"));
    }
  }

  private static boolean isProjectFile(final VirtualFile file) {
    return (!file.isDirectory() && file.getName().toLowerCase().endsWith(ProjectFileType.DOT_DEFAULT_EXTENSION)) ||
           (ProjectUtil.getImportProvider(file) != null);
  }

  private static boolean isProjectDirectory(final VirtualFile virtualFile) {
    if (virtualFile.isDirectory() && virtualFile.findChild(Project.DIRECTORY_STORE_FOLDER) != null) return true;
    return false;
  }
}
package com.github.drafael.chat4j.chat;

import org.apache.commons.lang3.StringUtils;

import javax.swing.JComponent;
import javax.swing.TransferHandler;
import javax.swing.text.JTextComponent;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

final class FileDropTransferHandler extends TransferHandler {
    private final Consumer<List<Path>> fileDropHandler;

    FileDropTransferHandler(Consumer<List<Path>> fileDropHandler) {
        this.fileDropHandler = fileDropHandler;
    }

    @Override
    public int getSourceActions(JComponent c) {
        return c instanceof JTextComponent ? COPY_OR_MOVE : NONE;
    }

    @Override
    protected Transferable createTransferable(JComponent c) {
        if (c instanceof JTextComponent textComponent) {
            String selected = textComponent.getSelectedText();
            if (StringUtils.isNotEmpty(selected)) {
                return new StringSelection(selected);
            }
        }
        return null;
    }

    @Override
    protected void exportDone(JComponent source, Transferable data, int action) {
        if (action == MOVE && source instanceof JTextComponent textComponent) {
            textComponent.replaceSelection("");
        }
    }

    @Override
    public boolean canImport(TransferSupport support) {
        if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return true;
        }
        return support.getComponent() instanceof JTextComponent
                && support.isDataFlavorSupported(DataFlavor.stringFlavor);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean importData(TransferSupport support) {
        if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            try {
                List<File> files = (List<File>) support.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);
                List<Path> paths = files.stream().map(File::toPath).toList();
                fileDropHandler.accept(paths);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        if (support.getComponent() instanceof JTextComponent textComponent
                && support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            try {
                String text = (String) support.getTransferable()
                        .getTransferData(DataFlavor.stringFlavor);
                textComponent.replaceSelection(text);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        return false;
    }
}

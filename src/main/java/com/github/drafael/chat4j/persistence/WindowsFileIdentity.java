package com.github.drafael.chat4j.persistence;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Optional;
import org.apache.commons.lang3.SystemUtils;

/** Reads the stable volume/file identifier used when the Windows NIO provider exposes no file key. */
final class WindowsFileIdentity {

    private static final int SHARE_MODE = WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE | WinNT.FILE_SHARE_DELETE;
    private static final int OPEN_FLAGS = WinNT.FILE_FLAG_BACKUP_SEMANTICS | WinNT.FILE_FLAG_OPEN_REPARSE_POINT;

    private WindowsFileIdentity() {
    }

    static Optional<Key> read(Path path) {
        if (!SystemUtils.IS_OS_WINDOWS || !path.getFileSystem().supportedFileAttributeViews().contains("dos")) {
            return Optional.empty();
        }
        WinNT.HANDLE handle = null;
        try {
            Path realPath = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            handle = Kernel32.INSTANCE.CreateFile(
                    realPath.toString(),
                    0,
                    SHARE_MODE,
                    null,
                    WinNT.OPEN_EXISTING,
                    OPEN_FLAGS,
                    null
            );
            if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
                return Optional.empty();
            }
            var information = new WinBase.FILE_ID_INFO();
            boolean read = Kernel32.INSTANCE.GetFileInformationByHandleEx(
                    handle,
                    WinBase.FileIdInfo,
                    information.getPointer(),
                    new WinDef.DWORD(information.size())
            );
            if (!read) {
                return Optional.empty();
            }
            information.read();
            byte[] identifier = new byte[information.FileId.Identifier.length];
            for (int index = 0; index < identifier.length; index++) {
                identifier[index] = information.FileId.Identifier[index].byteValue();
            }
            return Optional.of(new Key(
                    information.VolumeSerialNumber,
                    HexFormat.of().formatHex(identifier)
            ));
        } catch (IOException | RuntimeException | LinkageError e) {
            return Optional.empty();
        } finally {
            if (handle != null && !WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
                try {
                    Kernel32.INSTANCE.CloseHandle(handle);
                } catch (RuntimeException | LinkageError ignored) {
                    // The identity read already failed closed; handle cleanup is best-effort.
                }
            }
        }
    }

    record Key(long volumeSerialNumber, String fileId) {
    }
}

/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package it.feio.android.omninotes.helpers;


import static it.feio.android.omninotes.OmniNotes.getAppContext;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_BACKUP_FOLDER_URI;

import android.content.Intent;
import com.pixplicity.easyprefs.library.Prefs;
import it.feio.android.omninotes.async.DataBackupIntentService;
import it.feio.android.omninotes.db.FlatFileHelper;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;


/**
 * Backup helper that creates/restores {@code .tar.gz} archives of the
 * entire notes directory.
 */
public final class BackupHelper {

  private BackupHelper() {
  }

  /**
   * Creates a {@code .tar.gz} archive of the notes directory.
   *
   * @param destFile the output file (e.g. {@code /sdcard/backup.tar.gz})
   */
  public static void exportBackup(File destFile) throws IOException {
    File notesRoot = new File(FlatFileHelper.getNotesDir());
    try (FileOutputStream fos = new FileOutputStream(destFile);
         BufferedOutputStream bos = new BufferedOutputStream(fos);
         GZIPOutputStream gzos = new GZIPOutputStream(bos);
         TarArchiveOutputStream tar = new TarArchiveOutputStream(gzos)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      addDirectoryToTar(tar, notesRoot, "");
    }
  }

  /**
   * Restores a {@code .tar.gz} archive by extracting it to the notes
   * directory, replacing existing content.
   *
   * @param archiveFile the backup archive to restore
   */
  public static void importBackup(File archiveFile) throws IOException {
    File notesRoot = new File(FlatFileHelper.getNotesDir());
    deleteDirectoryContents(notesRoot);

    try (FileInputStream fis = new FileInputStream(archiveFile);
         BufferedInputStream bis = new BufferedInputStream(fis);
         GZIPInputStream gzis = new GZIPInputStream(bis);
         TarArchiveInputStream tar = new TarArchiveInputStream(gzis)) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        File dest = new File(notesRoot, entry.getName());
        if (entry.isDirectory()) {
          dest.mkdirs();
        } else {
          dest.getParentFile().mkdirs();
          try (FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = tar.read(buf)) != -1) {
              fos.write(buf, 0, len);
            }
          }
        }
      }
    }
  }

  /**
   * Lists existing backup files in the backup folder.
   */
  public static File[] listBackups() {
    String backupPath = Prefs.getString(PREF_BACKUP_FOLDER_URI, null);
    if (backupPath == null || backupPath.isEmpty()) return new File[0];
    File backupDir = new File(backupPath);
    if (!backupDir.exists()) return new File[0];
    File[] files = backupDir.listFiles((dir, name) ->
        name.endsWith(".tar.gz") || name.endsWith(".tgz"));
    return files != null ? files : new File[0];
  }

  /**
   * Starts the backup service.
   */
  public static void startBackupService(String backupName) {
    Intent service = new Intent(getAppContext(), DataBackupIntentService.class);
    service.setAction(DataBackupIntentService.ACTION_DATA_EXPORT);
    service.putExtra(DataBackupIntentService.INTENT_BACKUP_NAME, backupName);
    getAppContext().startService(service);
  }

  /**
   * Returns the configured backup folder path, or null.
   */
  public static String getBackupFolderPath() {
    return Prefs.getString(PREF_BACKUP_FOLDER_URI, null);
  }

  private static void addDirectoryToTar(TarArchiveOutputStream tar, File dir, String base)
      throws IOException {
    File[] files = dir.listFiles();
    if (files == null) return;
    for (File file : files) {
      String entryName = base.isEmpty() ? file.getName() : base + "/" + file.getName();
      if (file.isDirectory()) {
        TarArchiveEntry entry = new TarArchiveEntry(file, entryName + "/");
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
        addDirectoryToTar(tar, file, entryName);
      } else {
        TarArchiveEntry entry = new TarArchiveEntry(file, entryName);
        entry.setSize(file.length());
        tar.putArchiveEntry(entry);
        try (FileInputStream fis = new FileInputStream(file)) {
          byte[] buf = new byte[8192];
          int len;
          while ((len = fis.read(buf)) != -1) {
            tar.write(buf, 0, len);
          }
        }
        tar.closeArchiveEntry();
      }
    }
  }

  private static void deleteDirectoryContents(File dir) {
    if (dir == null || !dir.exists()) return;
    File[] files = dir.listFiles();
    if (files == null) return;
    for (File file : files) {
      if (file.isDirectory()) {
        deleteDirectoryContents(file);
      }
      file.delete();
    }
  }
}

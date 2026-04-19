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

package it.feio.android.omninotes.async;

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static it.feio.android.omninotes.helpers.IntentHelper.immutablePendingIntentFlag;
import static it.feio.android.omninotes.utils.ConstantsBase.ACTION_RESTART_APP;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_BACKUP_FOLDER_URI;

import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import com.pixplicity.easyprefs.library.Prefs;
import it.feio.android.omninotes.MainActivity;
import it.feio.android.omninotes.OmniNotes;
import it.feio.android.omninotes.R;
import it.feio.android.omninotes.db.FlatFileHelper;
import it.feio.android.omninotes.helpers.BackupHelper;
import it.feio.android.omninotes.helpers.LogDelegate;
import it.feio.android.omninotes.helpers.notifications.NotificationChannels.NotificationChannelNames;
import it.feio.android.omninotes.helpers.notifications.NotificationsHelper;
import it.feio.android.omninotes.models.Note;
import it.feio.android.omninotes.utils.ReminderHelper;
import java.io.File;
import java.io.IOException;


public class DataBackupIntentService extends IntentService {

  public static final String INTENT_BACKUP_NAME = "backup_name";
  public static final String ACTION_DATA_EXPORT = "action_data_export";
  public static final String ACTION_DATA_IMPORT = "action_data_import";
  public static final String ACTION_DATA_DELETE = "action_data_delete";

  private NotificationsHelper mNotificationsHelper;

  public DataBackupIntentService() {
    super("DataBackupIntentService");
  }

  @Override
  protected void onHandleIntent(Intent intent) {
    mNotificationsHelper = new NotificationsHelper(this).start(NotificationChannelNames.BACKUPS,
        R.drawable.ic_content_save_white_24dp, getString(R.string.working));

    if (ACTION_DATA_EXPORT.equals(intent.getAction())) {
      exportData(intent);
    } else if (ACTION_DATA_IMPORT.equals(intent.getAction())) {
      importData(intent);
    } else if (ACTION_DATA_DELETE.equals(intent.getAction())) {
      deleteData(intent);
    }
  }

  private void exportData(Intent intent) {
    String backupName = intent.getStringExtra(INTENT_BACKUP_NAME);
    String backupFolder = Prefs.getString(PREF_BACKUP_FOLDER_URI, null);
    LogDelegate.d("Export starting: name=" + backupName + " folder=" + backupFolder);
    if (backupFolder == null) {
      mNotificationsHelper.finish(getString(R.string.data_export_failed), "No backup folder set");
      return;
    }

    File destFile = new File(backupFolder, backupName + ".tar.gz");
    destFile.getParentFile().mkdirs();
    try {
      BackupHelper.exportBackup(destFile);
      LogDelegate.d("Export completed: " + destFile.getAbsolutePath() + " size=" + destFile.length());
      mNotificationsHelper.finish(getString(R.string.data_export_completed),
          destFile.getAbsolutePath());
    } catch (Exception e) {
      LogDelegate.e("Backup export failed", e);
      mNotificationsHelper.finish(getString(R.string.data_export_failed), e.getMessage());
    }
  }

  private synchronized void importData(Intent intent) {
    String backupName = intent.getStringExtra(INTENT_BACKUP_NAME);
    String backupFolder = Prefs.getString(PREF_BACKUP_FOLDER_URI, null);
    if (backupFolder == null) {
      mNotificationsHelper.finish(getString(R.string.data_import_failed), "No backup folder set");
      return;
    }

    File archiveFile = new File(backupFolder, backupName);
    if (!archiveFile.exists()) {
      mNotificationsHelper.finish(getString(R.string.data_import_failed), "Backup not found");
      return;
    }

    try {
      BackupHelper.importBackup(archiveFile);
      // Force cache reload
      FlatFileHelper.getInstance(true);
      resetReminders();
      mNotificationsHelper.cancel();
      createNotification(intent, this, getString(R.string.data_import_completed),
          getString(R.string.click_to_refresh_application));
    } catch (IOException e) {
      LogDelegate.e("Backup import failed", e);
      mNotificationsHelper.finish(getString(R.string.data_import_failed), e.getMessage());
    }
  }

  private synchronized void deleteData(Intent intent) {
    String backupName = intent.getStringExtra(INTENT_BACKUP_NAME);
    String backupFolder = Prefs.getString(PREF_BACKUP_FOLDER_URI, null);
    if (backupFolder == null) return;

    File backupFile = new File(backupFolder, backupName);
    if (backupFile.exists() && backupFile.delete()) {
      mNotificationsHelper.finish(getString(R.string.data_deletion_completed),
          backupName + " " + getString(R.string.deleted));
    } else {
      mNotificationsHelper.finish(getString(R.string.data_deletion_error), backupName);
    }
  }

  private void createNotification(Intent intent, Context context, String title, String message) {
    Intent intentLaunch;
    if (ACTION_DATA_IMPORT.equals(intent.getAction())) {
      intentLaunch = new Intent(context, MainActivity.class);
      intentLaunch.setAction(ACTION_RESTART_APP);
    } else {
      intentLaunch = new Intent();
    }
    intentLaunch.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    intentLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent notifyIntent = PendingIntent.getActivity(context, 0, intentLaunch,
        immutablePendingIntentFlag(FLAG_UPDATE_CURRENT));

    NotificationsHelper notificationsHelper = new NotificationsHelper(context);
    notificationsHelper.createStandardNotification(NotificationChannelNames.BACKUPS,
            R.drawable.ic_content_save_white_24dp, title, notifyIntent)
        .setMessage(message).setRingtone(Prefs.getString("settings_notification_ringtone", null))
        .setLedActive();
    if (Prefs.getBoolean("settings_notification_vibration", true)) {
      notificationsHelper.setVibration();
    }
    notificationsHelper.show();
  }

  private void resetReminders() {
    LogDelegate.d("Resetting reminders");
    for (Note note : FlatFileHelper.getInstance().getNotesWithReminderNotFired()) {
      ReminderHelper.addReminder(OmniNotes.getAppContext(), note);
    }
  }
}

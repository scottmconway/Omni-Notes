/*
 * Copyright (C) 2013-2025 Federico Iosue (developer@omninotes.app)
 *
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
package it.feio.android.omninotes.db;

import static it.feio.android.checklistview.interfaces.Constants.UNCHECKED_SYM;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_FILTER_ARCHIVED_IN_CATEGORIES;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_FILTER_PAST_REMINDERS;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_SORTING_COLUMN;
import static it.feio.android.omninotes.utils.Navigation.checkNavigation;
import static java.util.regex.Pattern.MULTILINE;
import static java.util.stream.Collectors.toList;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import com.pixplicity.easyprefs.library.Prefs;
import it.feio.android.omninotes.OmniNotes;
import it.feio.android.omninotes.db.FrontMatterUtils.ParsedNote;
import it.feio.android.omninotes.helpers.LogDelegate;
import it.feio.android.omninotes.helpers.NotesHelper;
import it.feio.android.omninotes.models.Attachment;
import it.feio.android.omninotes.models.Category;
import it.feio.android.omninotes.models.Note;
import it.feio.android.omninotes.models.Stats;
import it.feio.android.omninotes.models.Tag;
import it.feio.android.omninotes.utils.Navigation;
import it.feio.android.omninotes.utils.TagsHelper;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.regex.Pattern;


/**
 * Flat-file backed implementation of {@link NoteDataStore}.
 *
 * <p>Notes are stored as markdown files with YAML front matter in
 * {@code /sdcard/omni_notes/}. Categories live in a {@code categories/}
 * subdirectory, and attachments in {@code attachments/{noteCreation}/}.</p>
 */
public class FlatFileHelper implements NoteDataStore {

  public static final String NOTES_DIR = "/sdcard/omni_notes";
  public static final String ACTIVE_DIR = NOTES_DIR + "/notes";
  public static final String ARCHIVE_DIR = NOTES_DIR + "/archive";
  public static final String TRASH_DIR = NOTES_DIR + "/trash";
  public static final String CATEGORIES_DIR = NOTES_DIR + "/categories";
  public static final String NOTE_FILENAME = "note.md";

  // Re-export column key constants so callers that reference DbHelper.KEY_* can migrate.
  // NoteLoaderTask and sorting logic depend on these values.
  public static final String TABLE_NOTES = "notes";
  public static final String TABLE_ATTACHMENTS = "attachments";
  public static final String TABLE_CATEGORY = "categories";

  public static final String KEY_ID = "creation";
  public static final String KEY_CREATION = "creation";
  public static final String KEY_LAST_MODIFICATION = "last_modification";
  public static final String KEY_TITLE = "title";
  public static final String KEY_CONTENT = "content";
  public static final String KEY_ARCHIVED = "archived";
  public static final String KEY_TRASHED = "trashed";
  public static final String KEY_REMINDER = "alarm";
  public static final String KEY_REMINDER_FIRED = "reminder_fired";
  public static final String KEY_RECURRENCE_RULE = "recurrence_rule";
  public static final String KEY_LATITUDE = "latitude";
  public static final String KEY_LONGITUDE = "longitude";
  public static final String KEY_ADDRESS = "address";
  public static final String KEY_CATEGORY = "category_id";
  public static final String KEY_LOCKED = "locked";
  public static final String KEY_CHECKLIST = "checklist";

  public static final String KEY_ATTACHMENT_ID = "attachment_id";
  public static final String KEY_ATTACHMENT_URI = "uri";
  public static final String KEY_ATTACHMENT_NAME = "name";
  public static final String KEY_ATTACHMENT_SIZE = "size";
  public static final String KEY_ATTACHMENT_LENGTH = "length";
  public static final String KEY_ATTACHMENT_MIME_TYPE = "mime_type";
  public static final String KEY_ATTACHMENT_NOTE_ID = "note_id";

  public static final String KEY_CATEGORY_ID = "category_id";
  public static final String KEY_CATEGORY_NAME = "name";
  public static final String KEY_CATEGORY_DESCRIPTION = "description";
  public static final String KEY_CATEGORY_COLOR = "color";

  private static FlatFileHelper instance = null;
  private final Context mContext;

  // In-memory caches - one per directory for lazy loading
  private List<Note> activeCache;
  private List<Note> archiveCache;
  private List<Note> trashCache;
  private ArrayList<Category> categoriesCache;


  // -------------------------------------------------------------------------
  // Singleton
  // -------------------------------------------------------------------------

  public static synchronized FlatFileHelper getInstance() {
    return getInstance(OmniNotes.getAppContext());
  }

  public static synchronized FlatFileHelper getInstance(Context context) {
    if (instance == null) {
      instance = new FlatFileHelper(context);
    }
    return instance;
  }

  public static synchronized FlatFileHelper getInstance(boolean forcedNewInstance) {
    if (instance == null || forcedNewInstance) {
      Context context = (instance == null || instance.mContext == null)
          ? OmniNotes.getAppContext()
          : instance.mContext;
      instance = new FlatFileHelper(context);
    }
    return instance;
  }

  private FlatFileHelper(Context context) {
    this.mContext = context;
    ensureDirectories();
  }


  // -------------------------------------------------------------------------
  // Directory setup
  // -------------------------------------------------------------------------

  private void ensureDirectories() {
    if (hasStorageAccess()) {
      new File(NOTES_DIR).mkdirs();
      new File(ACTIVE_DIR).mkdirs();
      new File(ARCHIVE_DIR).mkdirs();
      new File(TRASH_DIR).mkdirs();
      new File(CATEGORIES_DIR).mkdirs();
    }
  }

  /**
   * Returns {@code true} if the app has permission to read/write
   * {@link #NOTES_DIR} on external storage.  On Android 11+ this
   * requires {@code MANAGE_EXTERNAL_STORAGE}.
   */
  public static boolean hasStorageAccess() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      return Environment.isExternalStorageManager();
    }
    return true;
  }

  /**
   * Returns an intent that opens the system "All files access" settings
   * page for this app (API 30+).  On older versions returns {@code null}.
   */
  public static Intent getAllFilesAccessIntent(Context context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
      intent.setData(Uri.parse("package:" + context.getPackageName()));
      return intent;
    }
    return null;
  }


  // -------------------------------------------------------------------------
  // Note CRUD
  // -------------------------------------------------------------------------

  @Override
  public Note updateNote(Note note, boolean updateLastModification) {
    long creation = note.getCreation() != null
        ? note.getCreation()
        : Calendar.getInstance().getTimeInMillis();
    note.setCreation(creation);

    long lastModification = (note.getLastModification() != null && !updateLastModification)
        ? note.getLastModification()
        : Calendar.getInstance().getTimeInMillis();
    note.setLastModification(lastModification);

    LinkedHashMap<String, String> fields = FrontMatterUtils.buildNoteFields(
        note.getTitle(), creation, lastModification,
        note.getAlarm(),
        Boolean.TRUE.equals(note.isReminderFired()),
        note.getRecurrenceRule(),
        note.getLatitude() != null ? String.valueOf(note.getLatitude()) : "",
        note.getLongitude() != null ? String.valueOf(note.getLongitude()) : "",
        note.getAddress(),
        note.getCategory() != null ? note.getCategory().getId() : null,
        Boolean.TRUE.equals(note.isLocked()),
        Boolean.TRUE.equals(note.isChecklist()));

    String markdown = FrontMatterUtils.serialize(fields, note.getContent());

    File parentDir = directoryForNote(note);
    String slug = SlugUtils.slugify(note.getTitle(), creation);

    // Find and handle existing note directory (may need rename or move)
    File noteDir = resolveNoteDir(parentDir, slug, creation);
    noteDir.mkdirs();

    File noteFile = new File(noteDir, NOTE_FILENAME);
    writeFile(noteFile, markdown);
    // Copy any attachments that aren't already in the note directory
    copyAttachmentsToNoteDir(note, noteDir);

    updateNoteInCache(note);
    LogDelegate.d("Saved note '" + note.getTitle() + "' to " + noteDir.getPath());

    return note;
  }

  /**
   * Copies attachment files into the note directory if they don't already
   * reside there. Updates the attachment URIs to point to the local copies.
   */
  private void copyAttachmentsToNoteDir(Note note, File noteDir) {
    if (note.getAttachmentsList() == null || note.getAttachmentsList().isEmpty()) return;
    for (Attachment attachment : note.getAttachmentsList()) {
      if (attachment.getUri() == null || Uri.EMPTY.equals(attachment.getUri())) continue;

      File sourceFile = null;
      String scheme = attachment.getUri().getScheme();
      if ("file".equals(scheme)) {
        sourceFile = new File(attachment.getUri().getPath());
      }

      if (sourceFile == null || !sourceFile.exists()) continue;

      // Already in the note directory?
      if (sourceFile.getParentFile() != null
          && sourceFile.getParentFile().getAbsolutePath().equals(noteDir.getAbsolutePath())) {
        continue;
      }

      // Copy to note directory, preserving the original filename
      String fileName = attachment.getName() != null && !attachment.getName().isEmpty()
          ? attachment.getName()
          : sourceFile.getName();
      File dest = new File(noteDir, fileName);

      // Avoid collisions
      if (dest.exists() && !dest.getAbsolutePath().equals(sourceFile.getAbsolutePath())) {
        String base = fileName.contains(".")
            ? fileName.substring(0, fileName.lastIndexOf('.'))
            : fileName;
        String ext = fileName.contains(".")
            ? fileName.substring(fileName.lastIndexOf('.'))
            : "";
        int suffix = 1;
        while (dest.exists()) {
          dest = new File(noteDir, base + "-" + suffix + ext);
          suffix++;
        }
      }

      try {
        org.apache.commons.io.FileUtils.moveFile(sourceFile, dest);
        attachment.setUri(Uri.fromFile(dest));
        attachment.setName(dest.getName());
        attachment.setSize(dest.length());
      } catch (IOException e) {
        LogDelegate.e("Failed to copy attachment to note dir: " + sourceFile.getName(), e);
      }
    }
  }

  /**
   * Returns the parent directory (active/archive/trash) for a note.
   */
  private File directoryForNote(Note note) {
    if (Boolean.TRUE.equals(note.isTrashed())) {
      return new File(TRASH_DIR);
    } else if (Boolean.TRUE.equals(note.isArchived())) {
      return new File(ARCHIVE_DIR);
    }
    return notesDir();
  }

  /**
   * Finds the slug directory for a note by creation ID across all parent
   * directories. If found, renames/moves it to the target parent + slug.
   * If not found, returns a new directory path.
   */
  private File resolveNoteDir(File targetParent, String slug, long creation) {
    File target = resolveUniqueDirName(targetParent, slug, creation);

    // Search all directories for an existing note dir with this creation ID
    for (File parent : new File[]{notesDir(), new File(ARCHIVE_DIR), new File(TRASH_DIR)}) {
      File[] dirs = parent.listFiles(File::isDirectory);
      if (dirs == null) continue;
      for (File dir : dirs) {
        // Skip non-note directories
        if (dir.getName().equals("archive") || dir.getName().equals("trash")
            || dir.getName().equals("categories")) continue;
        File md = new File(dir, NOTE_FILENAME);
        if (!md.exists()) continue;
        ParsedNote parsed = FrontMatterUtils.parse(md);
        if (parsed != null && parsed.getLong("creation", 0) == creation) {
          // Found existing - if it's already at the right path, return it
          if (dir.getAbsolutePath().equals(target.getAbsolutePath())) {
            return dir;
          }
          // Move/rename the directory
          dir.renameTo(target);
          return target;
        }
      }
    }
    return target;
  }

  /**
   * Returns a unique directory name under the parent, appending a numeric
   * suffix if the slug collides with a different note.
   */
  private File resolveUniqueDirName(File parent, String slug, long creation) {
    File candidate = new File(parent, slug);
    if (!candidate.exists()) return candidate;

    // Check if it belongs to this note
    File md = new File(candidate, NOTE_FILENAME);
    if (md.exists()) {
      ParsedNote parsed = FrontMatterUtils.parse(md);
      if (parsed != null && parsed.getLong("creation", 0) == creation) {
        return candidate;
      }
    }

    // Slug collision - add numeric suffix
    int suffix = 1;
    while (true) {
      candidate = new File(parent, slug + "-" + suffix);
      if (!candidate.exists()) return candidate;
      md = new File(candidate, NOTE_FILENAME);
      if (md.exists()) {
        ParsedNote parsed = FrontMatterUtils.parse(md);
        if (parsed != null && parsed.getLong("creation", 0) == creation) {
          return candidate;
        }
      }
      suffix++;
    }
  }


  @Override
  public Note getNote(long id) {
    // Search all three directories
    for (Note note : loadAllNotes()) {
      if (note.getCreation() != null && note.getCreation() == id) {
        return note;
      }
    }
    return null;
  }


  @Override
  public List<Note> getAllNotes(Boolean checkNavigation) {
    if (Boolean.TRUE.equals(checkNavigation)) {
      int navigation = Navigation.getNavigation();
      switch (navigation) {
        case Navigation.NOTES:
          return getNotesActive();
        case Navigation.ARCHIVE:
          return getNotesArchived();
        case Navigation.REMINDERS:
          return getNotesWithReminder(Prefs.getBoolean(PREF_FILTER_PAST_REMINDERS, false));
        case Navigation.TRASH:
          return getNotesTrashed();
        case Navigation.UNCATEGORIZED:
          return getNotesUncategorized();
        case Navigation.CATEGORY:
          return getNotesByCategory(Navigation.getCategory());
        default:
          return loadAllNotesSorted();
      }
    }
    return loadAllNotesSorted();
  }


  @Override
  public List<Note> getNotesActive() {
    List<Note> notes = loadNotesFromDir(notesDir(), activeCache);
    activeCache = new ArrayList<>(notes);
    sortNotes(notes);
    return notes;
  }


  @Override
  public List<Note> getNotesArchived() {
    List<Note> notes = loadNotesFromDir(new File(ARCHIVE_DIR), archiveCache);
    archiveCache = new ArrayList<>(notes);
    sortNotes(notes);
    return notes;
  }


  @Override
  public List<Note> getNotesTrashed() {
    List<Note> notes = loadNotesFromDir(new File(TRASH_DIR), trashCache);
    trashCache = new ArrayList<>(notes);
    sortNotes(notes);
    return notes;
  }


  @Override
  public List<Note> getNotesUncategorized() {
    return getNotesActive().stream()
        .filter(n -> n.getCategory() == null || n.getCategory().getId() == null
            || n.getCategory().getId() == 0)
        .collect(toList());
  }


  @Override
  public List<Note> getNotesWithLocation() {
    return loadAllNotesSorted().stream()
        .filter(n -> {
          try {
            return n.getLongitude() != null && Double.parseDouble(String.valueOf(n.getLongitude())) != 0;
          } catch (NumberFormatException e) {
            return false;
          }
        })
        .collect(toList());
  }


  /**
   * In the flat-file implementation the {@code whereCondition} parameter is
   * ignored because there is no SQL engine. All filtering is handled by the
   * specific query methods. This method simply returns all notes, optionally
   * sorted.
   */
  @Override
  public List<Note> getNotes(String whereCondition, boolean order) {
    return order ? loadAllNotesSorted() : loadAllNotes();
  }


  @Override
  public void archiveNote(Note note, boolean archive) {
    note.setArchived(archive);
    updateNote(note, false);
  }


  @Override
  public void trashNote(Note note, boolean trash) {
    note.setTrashed(trash);
    updateNote(note, false);
  }


  @Override
  public boolean deleteNote(Note note) {
    return deleteNote(note, false);
  }


  @Override
  public boolean deleteNote(Note note, boolean keepAttachments) {
    return deleteNote(note.get_id(), keepAttachments);
  }


  @Override
  public boolean deleteNote(long noteId, boolean keepAttachments) {
    // Find and delete the note directory from whichever parent it's in
    for (File parent : new File[]{notesDir(), new File(ARCHIVE_DIR), new File(TRASH_DIR)}) {
      File noteDir = findNoteDirByCreation(parent, noteId);
      if (noteDir != null) {
        if (keepAttachments) {
          // Only delete note.md, keep other files
          new File(noteDir, NOTE_FILENAME).delete();
          // Remove dir only if now empty
          String[] remaining = noteDir.list();
          if (remaining == null || remaining.length == 0) {
            noteDir.delete();
          }
        } else {
          deleteDirectory(noteDir);
        }
        break;
      }
    }
    removeNoteFromCache(noteId);
    return true;
  }

  /**
   * Finds a note's slug directory by scanning for a {@code note.md}
   * with the given creation ID.
   */
  private File findNoteDirByCreation(File parent, long creation) {
    File[] dirs = parent.listFiles(File::isDirectory);
    if (dirs == null) return null;
    for (File dir : dirs) {
      String name = dir.getName();
      if ("archive".equals(name) || "trash".equals(name) || "categories".equals(name)) continue;
      File md = new File(dir, NOTE_FILENAME);
      if (!md.exists()) continue;
      ParsedNote parsed = FrontMatterUtils.parse(md);
      if (parsed != null && parsed.getLong("creation", 0) == creation) {
        return dir;
      }
    }
    return null;
  }


  @Override
  public void emptyTrash() {
    for (Note note : getNotesTrashed()) {
      deleteNote(note);
    }
  }


  // -------------------------------------------------------------------------
  // Note queries
  // -------------------------------------------------------------------------

  @Override
  public List<Note> getNotesByPattern(String pattern) {
    if (pattern == null || pattern.isEmpty()) {
      return loadAllNotesSorted();
    }
    String lowerPattern = pattern.toLowerCase(Locale.ROOT);
    int navigation = Navigation.getNavigation();

    return loadAllNotesSorted().stream()
        .filter(n -> {
          // Respect navigation context
          if (navigation == Navigation.TRASH) {
            if (!Boolean.TRUE.equals(n.isTrashed())) return false;
          } else {
            if (Boolean.TRUE.equals(n.isTrashed())) return false;
          }
          if (navigation == Navigation.ARCHIVE && !Boolean.TRUE.equals(n.isArchived())) {
            return false;
          }
          if (navigation == Navigation.CATEGORY) {
            Long catId = Navigation.getCategory();
            if (catId != null && (n.getCategory() == null
                || !catId.equals(n.getCategory().getId()))) {
              return false;
            }
          }
          if (navigation == Navigation.UNCATEGORIZED) {
            if (n.getCategory() != null && n.getCategory().getId() != null
                && n.getCategory().getId() != 0) {
              return false;
            }
          }
          if (checkNavigation(Navigation.REMINDERS) && n.getAlarm() == null) {
            return false;
          }

          // Search title and content (locked notes only search title)
          String title = n.getTitle() != null ? n.getTitle().toLowerCase(Locale.ROOT) : "";
          String content = n.getContent() != null ? n.getContent().toLowerCase(Locale.ROOT) : "";

          if (Boolean.TRUE.equals(n.isLocked())) {
            return title.contains(lowerPattern);
          }
          return title.contains(lowerPattern) || content.contains(lowerPattern);
        })
        .collect(toList());
  }


  @Override
  public List<Note> getNotesWithReminder(boolean filterPastReminders) {
    long now = Calendar.getInstance().getTimeInMillis();
    return loadAllNotesSorted().stream()
        .filter(n -> !Boolean.TRUE.equals(n.isArchived()) && !Boolean.TRUE.equals(n.isTrashed()))
        .filter(n -> {
          if (n.getAlarm() == null) return false;
          if (filterPastReminders) {
            try {
              return Long.parseLong(n.getAlarm()) >= now;
            } catch (NumberFormatException e) {
              return false;
            }
          }
          return true;
        })
        .collect(toList());
  }


  @Override
  public List<Note> getNotesWithReminderNotFired() {
    return loadAllNotesSorted().stream()
        .filter(n -> n.getAlarm() != null
            && !Boolean.TRUE.equals(n.isReminderFired())
            && !Boolean.TRUE.equals(n.isArchived())
            && !Boolean.TRUE.equals(n.isTrashed()))
        .collect(toList());
  }


  @Override
  public List<Note> getNotesWithLock(boolean locked) {
    return loadAllNotesSorted().stream()
        .filter(n -> Boolean.TRUE.equals(n.isLocked()) == locked)
        .collect(toList());
  }


  @Override
  public List<Note> getTodayReminders() {
    Calendar todayStart = Calendar.getInstance();
    todayStart.set(Calendar.HOUR_OF_DAY, 0);
    todayStart.set(Calendar.MINUTE, 0);
    todayStart.set(Calendar.SECOND, 0);
    todayStart.set(Calendar.MILLISECOND, 0);
    long dayStart = todayStart.getTimeInMillis();
    long dayEnd = dayStart + 86400000L;

    return loadAllNotes().stream()
        .filter(n -> !Boolean.TRUE.equals(n.isTrashed()))
        .filter(n -> {
          if (n.getAlarm() == null) return false;
          try {
            long alarm = Long.parseLong(n.getAlarm());
            return alarm >= dayStart && alarm < dayEnd;
          } catch (NumberFormatException e) {
            return false;
          }
        })
        .collect(toList());
  }


  @Override
  public List<Note> getNotesByCategory(Long categoryId) {
    if (categoryId == null) {
      return getAllNotes(true);
    }
    boolean filterArchived = Prefs.getBoolean(
        PREF_FILTER_ARCHIVED_IN_CATEGORIES + categoryId, false);

    return loadAllNotesSorted().stream()
        .filter(n -> !Boolean.TRUE.equals(n.isTrashed()))
        .filter(n -> n.getCategory() != null && categoryId.equals(n.getCategory().getId()))
        .filter(n -> !filterArchived || !Boolean.TRUE.equals(n.isArchived()))
        .collect(toList());
  }


  @Override
  public List<Note> getNotesByTag(String tag) {
    return tag.contains(",")
        ? getNotesByTag(tag.split(","))
        : getNotesByTag(new String[]{tag});
  }


  @Override
  public List<Note> getNotesByTag(String[] tags) {
    boolean inTrash = checkNavigation(Navigation.TRASH);
    return loadAllNotesSorted().stream()
        .filter(n -> Boolean.TRUE.equals(n.isTrashed()) == inTrash)
        .filter(n -> {
          String searchText;
          if (Boolean.TRUE.equals(n.isLocked())) {
            searchText = n.getTitle() != null ? n.getTitle() : "";
          } else {
            searchText = (n.getTitle() != null ? n.getTitle() : "") + " "
                + (n.getContent() != null ? n.getContent() : "");
          }
          return Arrays.stream(tags).allMatch(tag -> searchText.contains(tag));
        })
        // Refine with word-boundary matching (same as DbHelper)
        .filter(n -> {
          String text = (n.getTitle() != null ? n.getTitle() : "") + " "
              + (n.getContent() != null ? n.getContent() : "");
          return Arrays.stream(tags).allMatch(tag -> {
            var p = Pattern.compile(".*(\\s|^)" + tag + "(\\s|$).*", MULTILINE);
            return p.matcher(text).find();
          });
        })
        .collect(toList());
  }


  @Override
  public List<Note> getChecklists() {
    return loadAllNotes().stream()
        .filter(n -> Boolean.TRUE.equals(n.isChecklist()))
        .collect(toList());
  }


  @Override
  public List<Note> getMasked() {
    return loadAllNotes().stream()
        .filter(n -> Boolean.TRUE.equals(n.isLocked()))
        .collect(toList());
  }


  @Override
  public List<Note> getNotesByUncompleteChecklist() {
    boolean inTrash = checkNavigation(Navigation.TRASH);
    return loadAllNotes().stream()
        .filter(n -> Boolean.TRUE.equals(n.isChecklist()))
        .filter(n -> n.getContent() != null && n.getContent().contains(String.valueOf(UNCHECKED_SYM)))
        .filter(n -> Boolean.TRUE.equals(n.isTrashed()) == inTrash)
        .collect(toList());
  }


  // -------------------------------------------------------------------------
  // Attachment operations
  // -------------------------------------------------------------------------

  @Override
  public Attachment updateAttachment(Attachment attachment) {
    // Attachments are stored as part of the note's front matter.
    // Individual attachment update outside a note save is a no-op in flat-file
    // mode; the caller is expected to update via updateNote().
    if (attachment.getId() == null) {
      attachment.setId(Calendar.getInstance().getTimeInMillis());
    }
    return attachment;
  }


  @Override
  public List<Attachment> getNoteAttachments(Note note) {
    if (note == null || note.get_id() == null) {
      return new ArrayList<>();
    }
    // Attachments are embedded in the note's front matter; just return what's
    // already loaded on the Note object.  If the caller needs a fresh read,
    // they should call getNote() first.
    return note.getAttachmentsList() != null
        ? note.getAttachmentsList()
        : new ArrayList<>();
  }


  @Override
  public ArrayList<Attachment> getAllAttachments() {
    ArrayList<Attachment> all = new ArrayList<>();
    for (Note note : loadAllNotes()) {
      if (note.getAttachmentsList() != null) {
        for (Attachment a : note.getAttachmentsList()) {
          a.setNoteId(note.get_id());
          all.add(a);
        }
      }
    }
    return all;
  }


  /**
   * In the flat-file implementation the SQL {@code whereCondition} is ignored.
   * Returns all attachments.
   */
  @Override
  public ArrayList<Attachment> getAttachments(String whereCondition) {
    return getAllAttachments();
  }


  // -------------------------------------------------------------------------
  // Category operations
  // -------------------------------------------------------------------------

  @Override
  public ArrayList<Category> getCategories() {
    if (categoriesCache != null) {
      return new ArrayList<>(categoriesCache);
    }
    ArrayList<Category> categories = new ArrayList<>();
    File catDir = new File(CATEGORIES_DIR);
    File[] files = catDir.listFiles((dir, name) -> name.endsWith(".md"));
    if (files == null) {
      return categories;
    }

    for (File file : files) {
      Category cat = parseCategoryFile(file);
      if (cat != null) {
        cat.setCount(getCategorizedCount(cat));
        categories.add(cat);
      }
    }

    categories.sort(Comparator.comparing(
        c -> c.getName() != null ? c.getName().toLowerCase(Locale.ROOT) : "zzzzzzzz"));
    categoriesCache = new ArrayList<>(categories);
    return categories;
  }


  @Override
  public Category updateCategory(Category category) {
    if (category.getId() == null) {
      category.setId(Calendar.getInstance().getTimeInMillis());
    }

    // Remove old file if exists (name may have changed)
    removeOldCategoryFile(category.getId());

    LinkedHashMap<String, String> fields = FrontMatterUtils.buildCategoryFields(
        category.getId(),
        category.getName(),
        category.getDescription(),
        category.getColor());

    String markdown = FrontMatterUtils.serialize(fields, "");
    String slug = SlugUtils.slugify(category.getName(), category.getId());
    File catFile = new File(CATEGORIES_DIR, slug + ".md");
    writeFile(catFile, markdown);

    invalidateCategoriesCache();
    return category;
  }


  @Override
  public long deleteCategory(Category category) {
    // Un-categorize notes with this category
    for (Note note : loadAllNotes()) {
      if (note.getCategory() != null
          && category.getId().equals(note.getCategory().getId())) {
        note.setCategory(null);
        updateNote(note, false);
      }
    }

    // Delete category file
    removeOldCategoryFile(category.getId());
    invalidateCategoriesCache();
    return 1;
  }


  @Override
  public Category getCategory(Long id) {
    if (id == null) {
      return null;
    }
    File catDir = new File(CATEGORIES_DIR);
    File[] files = catDir.listFiles((dir, name) -> name.endsWith(".md"));
    if (files == null) {
      return null;
    }
    for (File file : files) {
      Category cat = parseCategoryFile(file);
      if (cat != null && id.equals(cat.getId())) {
        return cat;
      }
    }
    return null;
  }


  @Override
  public int getCategorizedCount(Category category) {
    if (category == null || category.getId() == null) {
      return 0;
    }
    int count = 0;
    for (Note note : loadAllNotes()) {
      if (note.getCategory() != null
          && category.getId().equals(note.getCategory().getId())) {
        count++;
      }
    }
    return count;
  }


  // -------------------------------------------------------------------------
  // Tag operations
  // -------------------------------------------------------------------------

  @Override
  public List<Tag> getTags() {
    return getTags(null);
  }


  @Override
  public List<Tag> getTags(Note note) {
    List<Tag> tags = new ArrayList<>();
    HashMap<String, Integer> tagsMap = new HashMap<>();

    List<Note> notesToScan;
    if (note != null) {
      notesToScan = Collections.singletonList(note);
    } else {
      boolean inTrash = checkNavigation(Navigation.TRASH);
      notesToScan = loadAllNotes().stream()
          .filter(n -> Boolean.TRUE.equals(n.isTrashed()) == inTrash)
          .filter(n -> !Boolean.TRUE.equals(n.isLocked()))
          .filter(n -> {
            String text = (n.getTitle() != null ? n.getTitle() : "")
                + (n.getContent() != null ? n.getContent() : "");
            return text.contains("#");
          })
          .collect(toList());
    }

    for (Note n : notesToScan) {
      Map<String, Integer> tagsRetrieved = TagsHelper.retrieveTags(n);
      for (String s : tagsRetrieved.keySet()) {
        int count = tagsMap.getOrDefault(s, 0);
        tagsMap.put(s, ++count);
      }
    }

    for (Entry<String, Integer> entry : tagsMap.entrySet()) {
      tags.add(new Tag(entry.getKey(), entry.getValue()));
    }

    tags.sort((t1, t2) -> t1.getText().compareToIgnoreCase(t2.getText()));
    return tags;
  }


  // -------------------------------------------------------------------------
  // Statistics & reminders
  // -------------------------------------------------------------------------

  @Override
  public Stats getStats() {
    Stats mStats = new Stats();
    mStats.setCategories(getCategories().size());

    int notesActive = 0, notesArchived = 0, notesTrashed = 0;
    int reminders = 0, remindersFuture = 0;
    int checklists = 0, notesMasked = 0, tags = 0, locations = 0;
    int totalWords = 0, totalChars = 0, maxWords = 0, maxChars = 0;

    List<Note> notes = loadAllNotes();
    long now = Calendar.getInstance().getTimeInMillis();

    for (Note note : notes) {
      if (Boolean.TRUE.equals(note.isTrashed())) {
        notesTrashed++;
      } else if (Boolean.TRUE.equals(note.isArchived())) {
        notesArchived++;
      } else {
        notesActive++;
      }
      if (note.getAlarm() != null) {
        try {
          long alarm = Long.parseLong(note.getAlarm());
          if (alarm > 0) {
            if (alarm > now) {
              remindersFuture++;
            } else {
              reminders++;
            }
          }
        } catch (NumberFormatException ignored) {
        }
      }
      if (Boolean.TRUE.equals(note.isChecklist())) checklists++;
      if (Boolean.TRUE.equals(note.isLocked())) notesMasked++;
      tags += TagsHelper.retrieveTags(note).size();
      try {
        if (note.getLongitude() != null
            && Double.parseDouble(String.valueOf(note.getLongitude())) != 0) {
          locations++;
        }
      } catch (NumberFormatException ignored) {
      }
      int words = NotesHelper.getWords(note);
      int chars = NotesHelper.getChars(note);
      if (words > maxWords) maxWords = words;
      if (chars > maxChars) maxChars = chars;
      totalWords += words;
      totalChars += chars;
    }

    mStats.setNotesActive(notesActive);
    mStats.setNotesArchived(notesArchived);
    mStats.setNotesTrashed(notesTrashed);
    mStats.setReminders(reminders);
    mStats.setRemindersFutures(remindersFuture);
    mStats.setNotesChecklist(checklists);
    mStats.setNotesMasked(notesMasked);
    mStats.setTags(tags);
    mStats.setLocation(locations);

    int count = !notes.isEmpty() ? notes.size() : 1;
    mStats.setWords(totalWords);
    mStats.setWordsMax(maxWords);
    mStats.setWordsAvg(totalWords / count);
    mStats.setChars(totalChars);
    mStats.setCharsMax(maxChars);
    mStats.setCharsAvg(totalChars / count);

    // Attachment stats
    int images = 0, videos = 0, audioRecordings = 0, sketches = 0, filesCount = 0;
    List<Attachment> attachments = getAllAttachments();
    for (Attachment a : attachments) {
      String mime = a.getMime_type();
      if ("image/jpeg".equals(mime) || "image/*".equals(mime)) images++;
      else if ("video/mp4".equals(mime) || "video/*".equals(mime)) videos++;
      else if ("audio/amr".equals(mime) || "audio/*".equals(mime)) audioRecordings++;
      else if ("image/png".equals(mime)) sketches++;
      else filesCount++;
    }
    mStats.setAttachments(attachments.size());
    mStats.setImages(images);
    mStats.setVideos(videos);
    mStats.setAudioRecordings(audioRecordings);
    mStats.setSketches(sketches);
    mStats.setFiles(filesCount);

    return mStats;
  }


  @Override
  public void setReminderFired(long noteId, boolean fired) {
    Note note = getNote(noteId);
    if (note != null) {
      note.setReminderFired(fired ? 1 : 0);
      updateNote(note, false);
    }
  }


  // -------------------------------------------------------------------------
  // Internal helpers - note I/O
  // -------------------------------------------------------------------------

  private File notesDir() {
    return new File(ACTIVE_DIR);
  }

  /**
   * Loads every {@code .md} file from the notes directory and converts each
   * into a {@link Note}.
   */
  /**
   * Loads every {@code .md} file from the notes directory and converts each
   * into a {@link Note}. Results are cached; subsequent calls return the
   * cached list until the cache is invalidated by a write operation.
   */
  /**
   * Loads notes from all three directories (active, archive, trash).
   * The archived/trashed state is set based on which directory the
   * note was found in.
   */
  private List<Note> loadAllNotes() {
    List<Note> all = new ArrayList<>();
    all.addAll(loadActiveNotes());
    all.addAll(loadArchivedNotes());
    all.addAll(loadTrashedNotes());
    return all;
  }

  private List<Note> loadActiveNotes() {
    return loadNotesFromDir(notesDir(), activeCache);
  }

  private List<Note> loadArchivedNotes() {
    return loadNotesFromDir(new File(ARCHIVE_DIR), archiveCache);
  }

  private List<Note> loadTrashedNotes() {
    return loadNotesFromDir(new File(TRASH_DIR), trashCache);
  }

  /**
   * Loads notes from a parent directory by scanning subdirectories for
   * {@code note.md}. Attachments are discovered as sibling files.
   */
  private List<Note> loadNotesFromDir(File dir, List<Note> cache) {
    if (cache != null) {
      return new ArrayList<>(cache);
    }
    List<Note> notes = new ArrayList<>();
    File[] subdirs = dir.listFiles(File::isDirectory);
    if (subdirs == null || subdirs.length == 0) {
      return notes;
    }
    boolean isArchive = dir.getAbsolutePath().equals(new File(ARCHIVE_DIR).getAbsolutePath());
    boolean isTrash = dir.getAbsolutePath().equals(new File(TRASH_DIR).getAbsolutePath());
    for (File noteDir : subdirs) {
      // Skip special directories
      String name = noteDir.getName();
      if ("archive".equals(name) || "trash".equals(name) || "categories".equals(name)) continue;

      File mdFile = new File(noteDir, NOTE_FILENAME);
      if (!mdFile.exists()) continue;

      ParsedNote parsed = FrontMatterUtils.parse(mdFile);
      if (parsed != null) {
        Note note = buildNoteFromParsed(parsed);
        if (note != null) {
          note.setArchived(isArchive);
          note.setTrashed(isTrash);
          note.setAttachmentsList(discoverAttachments(noteDir, note.getCreation()));
          notes.add(note);
        }
      }
    }
    return notes;
  }

  /**
   * Discovers attachments by listing all files in a note directory
   * that are not {@code note.md}. Returns them in lexicographic order.
   * MIME types are detected from file headers.
   */
  private ArrayList<Attachment> discoverAttachments(File noteDir, Long noteCreation) {
    ArrayList<Attachment> attachments = new ArrayList<>();
    File[] files = noteDir.listFiles(f -> f.isFile() && !NOTE_FILENAME.equals(f.getName()));
    if (files == null || files.length == 0) return attachments;

    Arrays.sort(files, Comparator.comparing(File::getName));
    for (File file : files) {
      String mime = detectMimeType(file);
      Attachment attachment = new Attachment(
          file.lastModified(),
          Uri.fromFile(file),
          file.getName(),
          file.length(),
          0,
          mime != null ? mime : "application/octet-stream");
      if (noteCreation != null) {
        attachment.setNoteId(noteCreation);
      }
      attachments.add(attachment);
    }
    return attachments;
  }

  /**
   * Detects MIME type by reading the file's magic bytes.
   */
  private String detectMimeType(File file) {
    try (java.io.InputStream is = new java.io.FileInputStream(file)) {
      byte[] header = new byte[12];
      int read = is.read(header);
      if (read < 4) return null;

      // JPEG: FF D8 FF
      if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF) {
        return "image/jpeg";
      }
      // PNG: 89 50 4E 47
      if (header[0] == (byte) 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47) {
        return "image/png";
      }
      // GIF: 47 49 46 38
      if (header[0] == 0x47 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x38) {
        return "image/gif";
      }
      // WebP: RIFF....WEBP
      if (read >= 12 && header[0] == 0x52 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x46
          && header[8] == 0x57 && header[9] == 0x45 && header[10] == 0x42 && header[11] == 0x50) {
        return "image/webp";
      }
      // MP4/MOV: ....ftyp
      if (read >= 8 && header[4] == 0x66 && header[5] == 0x74 && header[6] == 0x79 && header[7] == 0x70) {
        return "video/mp4";
      }
      // PDF: 25 50 44 46
      if (header[0] == 0x25 && header[1] == 0x50 && header[2] == 0x44 && header[3] == 0x46) {
        return "application/pdf";
      }
      // AMR: 23 21 41 4D 52
      if (read >= 5 && header[0] == 0x23 && header[1] == 0x21 && header[2] == 0x41
          && header[3] == 0x4D && header[4] == 0x52) {
        return "audio/amr";
      }
      // OGG: 4F 67 67 53
      if (header[0] == 0x4F && header[1] == 0x67 && header[2] == 0x67 && header[3] == 0x53) {
        return "audio/ogg";
      }
      // Try filename extension as fallback
      return mimeFromExtension(file.getName());
    } catch (IOException e) {
      return mimeFromExtension(file.getName());
    }
  }

  private String mimeFromExtension(String name) {
    if (name == null) return null;
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
    if (lower.endsWith(".png")) return "image/png";
    if (lower.endsWith(".gif")) return "image/gif";
    if (lower.endsWith(".webp")) return "image/webp";
    if (lower.endsWith(".mp4")) return "video/mp4";
    if (lower.endsWith(".3gp") || lower.endsWith(".3gpp")) return "video/3gpp";
    if (lower.endsWith(".amr")) return "audio/amr";
    if (lower.endsWith(".ogg")) return "audio/ogg";
    if (lower.endsWith(".pdf")) return "application/pdf";
    if (lower.endsWith(".txt")) return "text/plain";
    return null;
  }

  /**
   * Loads all notes and applies the user's preferred sorting.
   */
  private List<Note> loadAllNotesSorted() {
    List<Note> notes = loadAllNotes();
    sortNotes(notes);
    return notes;
  }

  /**
   * Updates a note in the correct per-directory cache.
   */
  private void updateNoteInCache(Note note) {
    // Remove from all caches first
    long id = note.getCreation() != null ? note.getCreation() : 0;
    if (id == 0) return;
    removeFromList(activeCache, id);
    removeFromList(archiveCache, id);
    removeFromList(trashCache, id);

    // Add to the correct cache
    if (Boolean.TRUE.equals(note.isTrashed())) {
      if (trashCache != null) trashCache.add(note);
    } else if (Boolean.TRUE.equals(note.isArchived())) {
      if (archiveCache != null) archiveCache.add(note);
    } else {
      if (activeCache != null) activeCache.add(note);
    }
  }

  private void removeNoteFromCache(long noteId) {
    removeFromList(activeCache, noteId);
    removeFromList(archiveCache, noteId);
    removeFromList(trashCache, noteId);
  }

  private void removeFromList(List<Note> list, long noteId) {
    if (list == null) return;
    list.removeIf(n -> n.getCreation() != null && n.getCreation() == noteId);
  }

  private void invalidateAllNoteCaches() {
    activeCache = null;
    archiveCache = null;
    trashCache = null;
  }

  private void invalidateCategoriesCache() {
    categoriesCache = null;
  }

  /**
   * Applies the user's preferred sort order (mirroring DbHelper's ORDER BY).
   */
  private void sortNotes(List<Note> notes) {
    String sortColumn = checkNavigation(Navigation.REMINDERS)
        ? KEY_REMINDER
        : Prefs.getString(PREF_SORTING_COLUMN, KEY_TITLE);

    Comparator<Note> comparator;

    if (KEY_TITLE.equals(sortColumn)) {
      comparator = (a, b) -> {
        String textA = ((a.getTitle() != null ? a.getTitle() : "")
            + (a.getContent() != null ? a.getContent() : "")).toLowerCase(Locale.ROOT);
        String textB = ((b.getTitle() != null ? b.getTitle() : "")
            + (b.getContent() != null ? b.getContent() : "")).toLowerCase(Locale.ROOT);
        return textA.compareTo(textB);
      };
    } else if (KEY_REMINDER.equals(sortColumn)) {
      comparator = (a, b) -> {
        long alarmA = parseAlarm(a);
        long alarmB = parseAlarm(b);
        return Long.compare(alarmA, alarmB);
      };
    } else if (KEY_CREATION.equals(sortColumn)) {
      comparator = Comparator.comparing(
          (Note n) -> n.getCreation() != null ? n.getCreation() : 0L).reversed();
    } else {
      // Default: last modification descending
      comparator = Comparator.comparing(
          (Note n) -> n.getLastModification() != null ? n.getLastModification() : 0L).reversed();
    }

    notes.sort(comparator);
  }

  private long parseAlarm(Note note) {
    if (note.getAlarm() == null || note.getAlarm().isEmpty()) {
      return 0L;
    }
    try {
      return Long.parseLong(note.getAlarm());
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  /**
   * Constructs a {@link Note} from parsed front matter and body.
   */
  private Note buildNoteFromParsed(ParsedNote parsed) {
    long creation = parsed.getLong("creation", 0);
    if (creation == 0) {
      return null;
    }

    Note note = new Note();
    note.setCreation(creation);
    note.setLastModification(parsed.getLong("last_modification", creation));
    note.setTitle(parsed.get("title", ""));
    note.setContent(parsed.body());
    note.setAlarm(emptyToNull(parsed.get("alarm")));
    note.setReminderFired(parsed.getBoolean("reminder_fired") ? 1 : 0);
    note.setRecurrenceRule(emptyToNull(parsed.get("recurrence_rule")));
    note.setLatitude(emptyToNull(parsed.get("latitude")));
    note.setLongitude(emptyToNull(parsed.get("longitude")));
    note.setAddress(emptyToNull(parsed.get("address")));
    note.setLocked(parsed.getBoolean("locked"));
    note.setChecklist(parsed.getBoolean("checklist"));

    // Category
    long categoryId = parsed.getLong("category_id", 0);
    if (categoryId != 0) {
      Category cat = getCategory(categoryId);
      note.setCategory(cat);
    }

    // Attachments are discovered from the note directory, not front matter

    return note;
  }

  // -------------------------------------------------------------------------
  // Internal helpers - category I/O
  // -------------------------------------------------------------------------

  private Category parseCategoryFile(File file) {
    ParsedNote parsed = FrontMatterUtils.parse(file);
    if (parsed == null) {
      return null;
    }
    long id = parsed.getLong("id", 0);
    if (id == 0) {
      return null;
    }
    return new Category(
        id,
        parsed.get("name", ""),
        parsed.get("description", ""),
        parsed.get("color", ""));
  }

  private void removeOldCategoryFile(long categoryId) {
    File catDir = new File(CATEGORIES_DIR);
    File[] files = catDir.listFiles((dir, name) -> name.endsWith(".md"));
    if (files == null) return;
    for (File file : files) {
      Category cat = parseCategoryFile(file);
      if (cat != null && cat.getId() == categoryId) {
        file.delete();
        return;
      }
    }
  }


  // -------------------------------------------------------------------------
  // Internal helpers - attachment serialization
  // -------------------------------------------------------------------------



  // -------------------------------------------------------------------------
  // Internal helpers - file I/O
  // -------------------------------------------------------------------------

  private void writeFile(File file, String content) {
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
      writer.write(content);
    } catch (IOException e) {
      LogDelegate.e("Error writing file " + file.getAbsolutePath(), e);
    }
  }

  private void deleteDirectory(File dir) {
    if (dir == null || !dir.exists()) return;
    File[] files = dir.listFiles();
    if (files != null) {
      for (File f : files) {
        if (f.isDirectory()) {
          deleteDirectory(f);
        } else {
          f.delete();
        }
      }
    }
    dir.delete();
  }

  private String emptyToNull(String value) {
    return (value != null && !value.isEmpty()) ? value : null;
  }
}

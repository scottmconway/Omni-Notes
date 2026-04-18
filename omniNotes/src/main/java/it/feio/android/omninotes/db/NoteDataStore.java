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
package it.feio.android.omninotes.db;

import it.feio.android.omninotes.models.Attachment;
import it.feio.android.omninotes.models.Category;
import it.feio.android.omninotes.models.Note;
import it.feio.android.omninotes.models.Stats;
import it.feio.android.omninotes.models.Tag;
import java.util.ArrayList;
import java.util.List;


/**
 * Abstraction over note persistence. Implementations may use SQLite ({@link FlatFileHelper})
 * or flat markdown files ({@link FlatFileHelper}).
 */
public interface NoteDataStore {

  // --- Note CRUD ---

  Note updateNote(Note note, boolean updateLastModification);

  Note getNote(long id);

  List<Note> getAllNotes(Boolean checkNavigation);

  List<Note> getNotesActive();

  List<Note> getNotesArchived();

  List<Note> getNotesTrashed();

  List<Note> getNotesUncategorized();

  List<Note> getNotesWithLocation();

  List<Note> getNotes(String whereCondition, boolean order);

  void archiveNote(Note note, boolean archive);

  void trashNote(Note note, boolean trash);

  boolean deleteNote(Note note);

  boolean deleteNote(Note note, boolean keepAttachments);

  boolean deleteNote(long noteId, boolean keepAttachments);

  void emptyTrash();

  // --- Note queries ---

  List<Note> getNotesByPattern(String pattern);

  List<Note> getNotesWithReminder(boolean filterPastReminders);

  List<Note> getNotesWithReminderNotFired();

  List<Note> getNotesWithLock(boolean locked);

  List<Note> getTodayReminders();

  List<Note> getNotesByCategory(Long categoryId);

  List<Note> getNotesByTag(String tag);

  List<Note> getNotesByTag(String[] tags);

  List<Note> getChecklists();

  List<Note> getMasked();

  List<Note> getNotesByUncompleteChecklist();

  // --- Attachment operations ---

  Attachment updateAttachment(Attachment attachment);

  List<Attachment> getNoteAttachments(Note note);

  ArrayList<Attachment> getAllAttachments();

  ArrayList<Attachment> getAttachments(String whereCondition);

  // --- Category operations ---

  ArrayList<Category> getCategories();

  Category updateCategory(Category category);

  long deleteCategory(Category category);

  Category getCategory(Long id);

  int getCategorizedCount(Category category);

  // --- Tag operations ---

  List<Tag> getTags();

  List<Tag> getTags(Note note);

  // --- Statistics & reminders ---

  Stats getStats();

  void setReminderFired(long noteId, boolean fired);
}

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
package it.feio.android.omninotes.async.notes;

import android.os.AsyncTask;
import de.greenrobot.event.EventBus;
import it.feio.android.omninotes.async.bus.NotesLoadedEvent;
import it.feio.android.omninotes.db.FlatFileHelper;
import it.feio.android.omninotes.helpers.LogDelegate;
import it.feio.android.omninotes.exceptions.NotesLoadingException;
import it.feio.android.omninotes.models.Note;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;


public class NoteLoaderTask extends AsyncTask<Object, Void, List<Note>> {

  private static final String ERROR_RETRIEVING_NOTES = "Error retrieving notes";

  private static NoteLoaderTask instance;

  private NoteLoaderTask() {
  }


  public static NoteLoaderTask getInstance() {

    if (instance != null) {
      if (instance.getStatus() == Status.RUNNING && !instance.isCancelled()) {
        instance.cancel(true);
      } else if (instance.getStatus() == Status.PENDING) {
        return instance;
      }
    }

    instance = new NoteLoaderTask();
    return instance;
  }


  @Override
  protected List<Note> doInBackground(Object... params) {

    String methodName = params[0].toString();
    FlatFileHelper db = FlatFileHelper.getInstance();

    try {
      if (params.length < 2 || params[1] == null) {
        Method method = db.getClass().getMethod(methodName);
        return (List<Note>) method.invoke(db);
      } else {
        Object methodArgs = params[1];
        Class[] paramClass = new Class[]{methodArgs.getClass()};
        Method method = db.getClass().getMethod(methodName, paramClass);
        return (List<Note>) method.invoke(db, paramClass[0].cast(methodArgs));
      }
    } catch (NoSuchMethodException e) {
      LogDelegate.e(ERROR_RETRIEVING_NOTES + ": method not found: " + methodName);
      return new ArrayList<>();
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      LogDelegate.e(ERROR_RETRIEVING_NOTES + ": " + methodName + " - " + cause.getMessage(), cause);
      return new ArrayList<>();
    } catch (Exception e) {
      LogDelegate.e(ERROR_RETRIEVING_NOTES + ": " + methodName, e);
      return new ArrayList<>();
    }
  }


  @Override
  protected void onPostExecute(List<Note> notes) {

    super.onPostExecute(notes);
    EventBus.getDefault().post(new NotesLoadedEvent(notes));
  }
}

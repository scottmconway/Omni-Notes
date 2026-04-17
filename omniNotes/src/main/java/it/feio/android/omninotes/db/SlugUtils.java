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

import java.util.Locale;
import java.util.regex.Pattern;


/**
 * Utility for generating URL-style slugs from note titles, used as filenames.
 *
 * <p>Rules:
 * <ul>
 *   <li>Lowercase ASCII alphanumerics and hyphens only</li>
 *   <li>Consecutive non-alphanumeric characters collapse to a single hyphen</li>
 *   <li>Leading/trailing hyphens are stripped</li>
 *   <li>Empty or null titles fall back to the creation timestamp</li>
 *   <li>Truncated to 64 characters to avoid filesystem limits</li>
 * </ul>
 */
public final class SlugUtils {

  private static final int MAX_SLUG_LENGTH = 64;
  private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
  private static final Pattern LEADING_TRAILING_HYPHEN = Pattern.compile("^-|-$");

  private SlugUtils() {
  }


  /**
   * Generates a slug from a note title.
   *
   * @param title    The note title (may be null or empty)
   * @param creation The creation timestamp, used as fallback for untitled notes
   * @return A filesystem-safe slug string
   */
  public static String slugify(String title, long creation) {
    if (title == null || title.trim().isEmpty()) {
      return String.valueOf(creation);
    }

    String slug = title.toLowerCase(Locale.US);
    slug = NON_ALNUM.matcher(slug).replaceAll("-");
    slug = LEADING_TRAILING_HYPHEN.matcher(slug).replaceAll("");

    if (slug.isEmpty()) {
      return String.valueOf(creation);
    }

    if (slug.length() > MAX_SLUG_LENGTH) {
      slug = slug.substring(0, MAX_SLUG_LENGTH);
      // Don't end on a hyphen after truncation
      if (slug.endsWith("-")) {
        slug = slug.substring(0, slug.length() - 1);
      }
    }

    return slug;
  }
}

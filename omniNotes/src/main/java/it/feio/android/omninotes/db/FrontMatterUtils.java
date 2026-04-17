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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * Utility for parsing and writing YAML-style front matter in markdown files.
 *
 * <p>Supports simple key-value pairs delimited by {@code ---} markers.
 * Values are stored as strings and converted by callers as needed.</p>
 *
 * <p>Example:
 * <pre>
 * ---
 * title: My Note
 * creation: 1672531200000
 * archived: false
 * ---
 * Body content here...
 * </pre>
 */
public final class FrontMatterUtils {

  private static final String DELIMITER = "---";

  private FrontMatterUtils() {
  }


  /**
   * Parsed result containing front matter key-value pairs and the body content.
   */
  public static class ParsedNote {

    private final Map<String, String> frontMatter;
    private final String body;

    public ParsedNote(Map<String, String> frontMatter, String body) {
      this.frontMatter = frontMatter;
      this.body = body;
    }

    public Map<String, String> getFrontMatter() {
      return frontMatter;
    }

    public String body() {
      return body;
    }

    public String get(String key) {
      return frontMatter.get(key);
    }

    public String get(String key, String defaultValue) {
      String value = frontMatter.get(key);
      return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    public long getLong(String key, long defaultValue) {
      String value = frontMatter.get(key);
      if (value == null || value.isEmpty()) {
        return defaultValue;
      }
      try {
        return Long.parseLong(value.trim());
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }

    public int getInt(String key, int defaultValue) {
      String value = frontMatter.get(key);
      if (value == null || value.isEmpty()) {
        return defaultValue;
      }
      try {
        return Integer.parseInt(value.trim());
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }

    public boolean getBoolean(String key) {
      String value = frontMatter.get(key);
      if (value == null || value.isEmpty()) {
        return false;
      }
      value = value.trim().toLowerCase();
      return "true".equals(value) || "1".equals(value) || "yes".equals(value);
    }
  }


  /**
   * Parses a markdown file with YAML front matter.
   *
   * @param file The markdown file to parse
   * @return A {@link ParsedNote} containing front matter and body, or null if file cannot be read
   */
  public static ParsedNote parse(File file) {
    try {
      String content = readFile(file);
      return parseString(content);
    } catch (IOException e) {
      return null;
    }
  }


  /**
   * Parses a markdown string with YAML front matter.
   */
  public static ParsedNote parseString(String content) {
    if (content == null || content.isEmpty()) {
      return new ParsedNote(new LinkedHashMap<>(), "");
    }

    Map<String, String> frontMatter = new LinkedHashMap<>();
    String body = "";

    String trimmed = content.trim();
    if (!trimmed.startsWith(DELIMITER)) {
      // No front matter - entire content is body
      return new ParsedNote(frontMatter, content);
    }

    int firstDelimEnd = content.indexOf('\n', content.indexOf(DELIMITER));
    if (firstDelimEnd < 0) {
      return new ParsedNote(frontMatter, content);
    }

    int secondDelimStart = content.indexOf('\n' + DELIMITER, firstDelimEnd);
    if (secondDelimStart < 0) {
      // Closing delimiter not found - treat as no front matter
      return new ParsedNote(frontMatter, content);
    }

    String frontMatterBlock = content.substring(firstDelimEnd + 1, secondDelimStart);
    int bodyStart = content.indexOf('\n', secondDelimStart + 1);
    body = (bodyStart >= 0 && bodyStart + 1 < content.length())
        ? content.substring(bodyStart + 1)
        : "";

    // Parse key-value pairs
    for (String line : frontMatterBlock.split("\n")) {
      if (line.trim().isEmpty()) {
        continue;
      }
      int colonIndex = line.indexOf(':');
      if (colonIndex > 0) {
        String key = line.substring(0, colonIndex).trim();
        String value = line.substring(colonIndex + 1).trim();
        frontMatter.put(key, value);
      }
    }

    return new ParsedNote(frontMatter, body);
  }


  /**
   * Serializes front matter and body into a markdown string with YAML front matter.
   *
   * @param fields Ordered key-value pairs for the front matter
   * @param body   The note body content
   * @return Complete markdown string
   */
  public static String serialize(Map<String, String> fields, String body) {
    StringBuilder sb = new StringBuilder();
    sb.append(DELIMITER).append('\n');

    for (Map.Entry<String, String> entry : fields.entrySet()) {
      sb.append(entry.getKey()).append(": ").append(safeValue(entry.getValue())).append('\n');
    }

    sb.append(DELIMITER).append('\n');

    if (body != null && !body.isEmpty()) {
      sb.append(body);
      if (!body.endsWith("\n")) {
        sb.append('\n');
      }
    }

    return sb.toString();
  }


  /**
   * Builds the front matter fields map for a note's metadata.
   * Callers can add/override fields before passing to {@link #serialize}.
   */
  public static LinkedHashMap<String, String> buildNoteFields(
      String title, long creation, long lastModification,
      boolean archived, boolean trashed,
      String alarm, boolean reminderFired, String recurrenceRule,
      String latitude, String longitude, String address,
      Long categoryId, boolean locked, boolean checklist,
      String attachmentsJson) {

    LinkedHashMap<String, String> fields = new LinkedHashMap<>();
    fields.put("title", safe(title));
    fields.put("creation", String.valueOf(creation));
    fields.put("last_modification", String.valueOf(lastModification));
    fields.put("archived", String.valueOf(archived));
    fields.put("trashed", String.valueOf(trashed));
    fields.put("alarm", safe(alarm));
    fields.put("reminder_fired", String.valueOf(reminderFired));
    fields.put("recurrence_rule", safe(recurrenceRule));
    fields.put("latitude", safe(latitude));
    fields.put("longitude", safe(longitude));
    fields.put("address", safe(address));
    fields.put("category_id", categoryId != null ? String.valueOf(categoryId) : "");
    fields.put("locked", String.valueOf(locked));
    fields.put("checklist", String.valueOf(checklist));
    fields.put("attachments_json", safe(attachmentsJson));
    return fields;
  }


  /**
   * Builds the front matter fields map for a category file.
   */
  public static LinkedHashMap<String, String> buildCategoryFields(
      long id, String name, String description, String color) {
    LinkedHashMap<String, String> fields = new LinkedHashMap<>();
    fields.put("id", String.valueOf(id));
    fields.put("name", safe(name));
    fields.put("description", safe(description));
    fields.put("color", safe(color));
    return fields;
  }


  private static String safe(String value) {
    return value != null ? value : "";
  }


  /**
   * Ensures a front-matter value does not introduce parsing issues.
   * If the value contains a colon followed by a space, it is left as-is
   * because our parser only splits on the first colon.
   */
  private static String safeValue(String value) {
    return value != null ? value : "";
  }


  private static String readFile(File file) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
      char[] buf = new char[8192];
      int read;
      while ((read = reader.read(buf)) != -1) {
        sb.append(buf, 0, read);
      }
    }
    return sb.toString();
  }
}

#!/usr/bin/env python3

"""
Convert Omni Notes JSON exports to flat-file markdown format.

Usage: python3 import_json.py <directory>

Scans <directory> for *.json files, converts each to the flat-file
directory-per-note structure:
  notes/<slug>/note.md     (active notes)
  archive/<slug>/note.md   (archived notes)
  trash/<slug>/note.md     (trashed notes)

The original JSON files are deleted after successful conversion.
"""

import json
import os
import re
import sys

MAX_SLUG_LENGTH = 64


def slugify(title, creation):
    if not title or not title.strip():
        return str(creation)
    slug = title.lower()
    slug = re.sub(r"[^a-z0-9]+", "-", slug)
    slug = slug.strip("-")
    if not slug:
        return str(creation)
    if len(slug) > MAX_SLUG_LENGTH:
        slug = slug[:MAX_SLUG_LENGTH].rstrip("-")
    return slug


def build_front_matter(note):
    fields = []
    fields.append(("title", note.get("title", "")))
    fields.append(("creation", str(note.get("creation", 0))))
    fields.append(
        (
            "last_modification",
            str(note.get("lastModification", note.get("creation", 0))),
        )
    )
    fields.append(("alarm", note.get("alarm", "")))
    fields.append(("reminder_fired", str(note.get("reminderFired", False)).lower()))
    fields.append(("recurrence_rule", note.get("recurrenceRule", "")))
    fields.append(("latitude", note.get("latitude", "")))
    fields.append(("longitude", note.get("longitude", "")))
    fields.append(("address", note.get("address", "")))

    category = note.get("category")
    if category and isinstance(category, dict):
        fields.append(("category_id", str(category.get("id", ""))))
    else:
        fields.append(("category_id", ""))

    fields.append(("checklist", str(note.get("checklist", False)).lower()))

    lines = ["---"]
    for key, value in fields:
        if value is None:
            value = ""
        lines.append(f"{key}: {value}")
    lines.append("---")
    return "\n".join(lines) + "\n"


def resolve_unique_dir(parent, slug):
    candidate = os.path.join(parent, slug)
    if not os.path.exists(candidate):
        return candidate
    suffix = 1
    while True:
        candidate = os.path.join(parent, f"{slug}-{suffix}")
        if not os.path.exists(candidate):
            return candidate
        suffix += 1


def convert_note(json_path, base_dir):
    with open(json_path, "r", encoding="utf-8") as f:
        note = json.load(f)

    # Determine target directory
    if note.get("trashed", False):
        parent = os.path.join(base_dir, "trash")
    elif note.get("archived", False):
        parent = os.path.join(base_dir, "archive")
    else:
        parent = os.path.join(base_dir, "notes")

    os.makedirs(parent, exist_ok=True)

    title = note.get("title", "")
    creation = note.get("creation", 0)
    slug = slugify(title, creation)
    note_dir = resolve_unique_dir(parent, slug)
    os.makedirs(note_dir, exist_ok=True)

    front_matter = build_front_matter(note)
    content = note.get("content", "")
    if content is None:
        content = ""

    md_content = front_matter + content
    if md_content and not md_content.endswith("\n"):
        md_content += "\n"

    note_file = os.path.join(note_dir, "note.md")
    with open(note_file, "w", encoding="utf-8") as f:
        f.write(md_content)

    return note_dir


def main():
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <directory>", file=sys.stderr)
        sys.exit(1)

    base_dir = sys.argv[1]
    if not os.path.isdir(base_dir):
        print(f"Error: {base_dir} is not a directory", file=sys.stderr)
        sys.exit(1)

    json_files = [
        f
        for f in os.listdir(base_dir)
        if f.endswith(".json") and os.path.isfile(os.path.join(base_dir, f))
    ]

    if not json_files:
        print("No JSON files found")
        sys.exit(0)

    # Create subdirectories
    for subdir in ("notes", "archive", "trash", "categories"):
        os.makedirs(os.path.join(base_dir, subdir), exist_ok=True)

    converted = 0
    for filename in sorted(json_files):
        json_path = os.path.join(base_dir, filename)
        try:
            note_dir = convert_note(json_path, base_dir)
            os.remove(json_path)
            converted += 1
            print(f"  {filename} -> {os.path.relpath(note_dir, base_dir)}/note.md")
        except Exception as e:
            print(f"  ERROR: {filename}: {e}", file=sys.stderr)

    print(f"\nConverted {converted}/{len(json_files)} notes")


if __name__ == "__main__":
    main()

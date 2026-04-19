 ![icon](assets/logo.png)

Omni-Notes
==========

This is a fork of [Federico Iosue's Omni Notes](https://github.com/federicoiosue/Omni-Notes) with _opinionated_ changes.
It is for my personal use, and may not be feature-complete or contribute to upstream.

You should probably use his version instead of this one!

## Fork Differences
### Notes are flat markdown files with YAML front-matter headers, rather than SQLite DB entries.
_Why_? because with the use of regular flat files, it's very easy to synchronize via a 3rd-party tool such as Syncthing.
Files can be stored in app-internal, app-external, or custom directories (requires full external storage access).
Also with this setup, files can be modified out-of-band by other clients.

Archive and Trash status is determined by the note's directory - `notes`, `archive`, or `trash`!

### Modified features
Data backup now simply tars the storage directory rather than creating an intermediary format.

### Feature removals
Password-lock functionality, photo notes, external synchronization (use syncthing or similar instead), springpad import, and possibly additional features have been removed.

### Non-foss build targets have been removed
I have no intention of ever building them, so they have been removed.

## License


    Copyright 2013-2025 Federico Iosue
    
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
    
    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
    
    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.


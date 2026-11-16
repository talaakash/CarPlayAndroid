import 'dart:convert';
import 'dart:io';

/// Files in the directory shared with the native Android Auto / CarPlay code.
///
/// Ownership (one writer per file, no cross-runtime races):
///  * channels.json   - written by Dart (PlaylistService), read by native
///  * favorites.json  - written by Dart (FavoriteService) and by native (car "favorite" button);
///                      Dart re-reads it before every toggle and on the native userData event
///  * recents.json    - written by native (the engine records what plays), read by Dart
class SharedFiles {
  SharedFiles(this.directory);
  final String directory;

  File file(String name) => File('$directory/$name');

  Future<Object?> readJson(String name) async {
    final f = file(name);
    if (!await f.exists()) return null;
    try {
      return jsonDecode(await f.readAsString());
    } catch (_) {
      return null; // corrupt/partial file: treat as missing
    }
  }

  /// Atomic write: temp file + rename.
  Future<void> writeString(String name, String content) async {
    final tmp = File('$directory/$name.tmp');
    await tmp.writeAsString(content, flush: true);
    await tmp.rename('$directory/$name');
  }
}

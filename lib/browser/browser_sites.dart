import 'package:material_ui/material_ui.dart';

/// A shortcut on the browser home screen.
class PopularSite {
  const PopularSite(this.name, this.url, this.domain, this.color);
  final String name;
  final String url;

  /// Used for the favicon (fetched live); [color] + initial are the offline fallback.
  final String domain;
  final Color color;
}

/// Popular websites shown on the browser home screen. Edit this list to change the shortcuts.
/// The car browser has its own copy in `PopularSites.kt` (keep both in sync).
const popularSites = <PopularSite>[
  PopularSite('Google', 'https://www.google.com', 'google.com', Color(0xFF4285F4)),
  PopularSite('YouTube', 'https://m.youtube.com', 'youtube.com', Color(0xFFFF0000)),
  PopularSite('Facebook', 'https://m.facebook.com', 'facebook.com', Color(0xFF1877F2)),
  PopularSite('Instagram', 'https://www.instagram.com', 'instagram.com', Color(0xFFE4405F)),
  PopularSite('X', 'https://x.com', 'x.com', Color(0xFF111111)),
  PopularSite('Wikipedia', 'https://en.m.wikipedia.org', 'wikipedia.org', Color(0xFF636466)),
  PopularSite('Reddit', 'https://www.reddit.com', 'reddit.com', Color(0xFFFF4500)),
  PopularSite('Amazon', 'https://www.amazon.com', 'amazon.com', Color(0xFFFF9900)),
];

String faviconUrl(String domain) => 'https://www.google.com/s2/favicons?domain=$domain&sz=128';

/// The configured search engine (query is appended, URL-encoded).
const searchEngineUrl = 'https://www.google.com/search?q=';

/// Turns what the user typed into something to load: a URL, a search, or null (empty).
class BrowserInput {
  BrowserInput._();

  static final _host = RegExp(
    r'^(localhost|(\d{1,3}\.){3}\d{1,3}|([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,63})(:\d{1,5})?([/?#].*)?$',
    caseSensitive: false,
  );

  /// `example.com`, `https://x.org/a?b`, `localhost:8080` -> URL. Anything else -> search.
  static String? resolve(String raw) {
    final t = raw.trim();
    if (t.isEmpty) return null;
    final scheme = RegExp(r'^([a-z][a-z0-9+.-]*):', caseSensitive: false).firstMatch(t)?.group(1)?.toLowerCase();
    if (scheme == 'http' || scheme == 'https') {
      final u = Uri.tryParse(t);
      if (u != null && u.host.isNotEmpty && !t.contains(' ')) return u.toString();
      return search(t);
    }
    // "localhost:8080" parses as scheme "localhost": handled by the host pattern below.
    if (!t.contains(RegExp(r'\s')) && _host.hasMatch(t)) return 'https://$t';
    return search(t);
  }

  static String search(String q) => '$searchEngineUrl${Uri.encodeQueryComponent(q.trim())}';

  /// Only http(s) pages can be mirrored on the car.
  static bool isWebUrl(String? url) {
    final u = url == null ? null : Uri.tryParse(url);
    return u != null && (u.scheme == 'http' || u.scheme == 'https') && u.host.isNotEmpty;
  }
}

/// Same page ignoring case, a trailing slash and the #fragment (mirrors `WebUrls.same` on the car).
bool sameBrowserPage(String a, String b) => _pageKey(a) == _pageKey(b);
String _pageKey(String u) {
  var k = u.split('#').first.toLowerCase();
  while (k.endsWith('/')) {
    k = k.substring(0, k.length - 1);
  }
  return k;
}

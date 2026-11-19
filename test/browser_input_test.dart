import 'package:car_play/browser/browser_sites.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('URLs are recognised, other text is searched', () {
    expect(BrowserInput.resolve('example.com'), 'https://example.com');
    expect(BrowserInput.resolve('https://a.org/x?y=1'), 'https://a.org/x?y=1');
    expect(BrowserInput.resolve('localhost:8080/a'), 'https://localhost:8080/a');
    expect(BrowserInput.resolve('192.168.1.1'), 'https://192.168.1.1');
    expect(BrowserInput.resolve('best pizza near me'), '${searchEngineUrl}best+pizza+near+me');
    expect(BrowserInput.resolve('flutter'), '${searchEngineUrl}flutter');
    expect(BrowserInput.resolve('javascript:alert(1)'), startsWith(searchEngineUrl));
    expect(BrowserInput.resolve('   '), isNull);
  });

  test('only http(s) pages can go to the car', () {
    expect(BrowserInput.isWebUrl('https://a.org'), isTrue);
    expect(BrowserInput.isWebUrl('intent://x'), isFalse);
    expect(BrowserInput.isWebUrl('file:///sdcard/a'), isFalse);
    expect(BrowserInput.isWebUrl(null), isFalse);
  });

  test('sameBrowserPage ignores case, trailing slash and fragment', () {
    expect(sameBrowserPage('https://Example.com/', 'https://example.com'), isTrue);
    expect(sameBrowserPage('https://example.com/a#x', 'https://example.com/a'), isTrue);
    expect(sameBrowserPage('https://example.com/a', 'https://example.com/b'), isFalse);
  });
}

import 'package:car_play/iptv/services/player_prefs_service.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:iptv_native/iptv_native.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('output and fit are applied optimistically and sent to the native layer', () async {
    final calls = <MethodCall>[];
    final m = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    m.setMockMethodCallHandler(const MethodChannel('iptv_native/control'), (c) async {
      calls.add(c);
      return null;
    });
    final prefs = PlayerPrefsService();
    var notified = 0;
    prefs.addListener(() => notified++);

    expect(prefs.output, VideoOutput.phone);
    await prefs.setOutput(VideoOutput.car);
    await prefs.setFit(VideoFit.fill);

    expect(prefs.output, VideoOutput.car);
    expect(prefs.fit, VideoFit.fill);
    expect(notified, 2);
    expect(calls.map((c) => c.method), ['setOutput', 'setFit']);
    expect(calls.first.arguments, {'output': 'car'});
  });
}

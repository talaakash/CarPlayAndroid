import 'package:car_play/iptv/automotive/automotive_navigation_service.dart';
import 'package:car_play/iptv/automotive/automotive_playback_bridge.dart';
import 'package:car_play/iptv/services/channel_service.dart';
import 'package:car_play/iptv/services/player_service.dart';
import 'package:car_play/iptv/services/playback_controller.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:iptv_native/iptv_native.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    final m = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    m.setMockMethodCallHandler(const MethodChannel('iptv_native/control'), (_) async => null);
    m.setMockMethodCallHandler(const MethodChannel('iptv_native/events'), (_) async => null);
  });

  group('AutomotiveNavigationService (loop prevention)', () {
    test('car selection is surfaced once; phone echoes and stale versions are ignored', () {
      final nav = AutomotiveNavigationService(IptvNative.instance);

      nav.handle(const BrowseSelection('cat:News', 'News', 1, CommandOrigin.car));
      expect(nav.remoteSelection.value?.scope, 'cat:News');
      expect(nav.scope, 'cat:News');

      nav.remoteSelection.value = null;
      // Our own change coming back from native: must not be re-applied.
      nav.handle(const BrowseSelection('favorites', 'Favorites', 2, CommandOrigin.phone));
      expect(nav.remoteSelection.value, isNull);
      expect(nav.scope, 'favorites');

      // Duplicate / older versions are dropped.
      nav.handle(const BrowseSelection('cat:Sports', 'Sports', 2, CommandOrigin.car));
      nav.handle(const BrowseSelection('cat:Sports', 'Sports', 1, CommandOrigin.car));
      expect(nav.remoteSelection.value, isNull);
      expect(nav.scope, 'favorites');
    });
  });

  group('AutomotivePlaybackBridge', () {
    test('drops stale or duplicate state events', () {
      final pc = PlaybackController(IPTVPlayerService(), ChannelService());
      final bridge = AutomotivePlaybackBridge(pc, IptvNative.instance);

      expect(bridge.accept(const NativePlaybackState(version: 5, origin: CommandOrigin.car)), isTrue);
      expect(bridge.accept(const NativePlaybackState(version: 5)), isFalse);
      expect(bridge.accept(const NativePlaybackState(version: 4)), isFalse);
      expect(bridge.accept(const NativePlaybackState(version: 6)), isTrue);
      expect(bridge.lastVersion, 6);
    });
  });

  test('connection / origin parsing', () {
    final c = CarConnectionInfo.fromMap({'known': true, 'connected': true, 'platform': 'carPlay'});
    expect(c.connected, isTrue);
    expect(c.platform, CarPlatform.carPlay);
    expect(CarConnectionInfo.fromMap({'known': false}).connected, isFalse);
    expect(CommandOrigin.parse('car'), CommandOrigin.car);
    expect(CommandOrigin.parse('garbage'), CommandOrigin.phone);
  });
}

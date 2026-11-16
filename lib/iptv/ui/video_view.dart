import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:iptv_native/iptv_native.dart';

/// Renders the video of the shared native player (no second player is created).
class NativeVideoView extends StatelessWidget {
  const NativeVideoView({super.key});

  @override
  Widget build(BuildContext context) {
    if (Platform.isAndroid) {
      return PlatformViewLink(
        viewType: IptvNative.viewType,
        surfaceFactory: (context, controller) => AndroidViewSurface(
          controller: controller as AndroidViewController,
          gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
          hitTestBehavior: PlatformViewHitTestBehavior.transparent,
        ),
        onCreatePlatformView: (params) {
          return PlatformViewsService.initExpensiveAndroidView(
            id: params.id,
            viewType: IptvNative.viewType,
            layoutDirection: TextDirection.ltr,
            creationParamsCodec: const StandardMessageCodec(),
            onFocus: () => params.onFocusChanged(true),
          )
            ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
            ..create();
        },
      );
    }
    if (Platform.isIOS) {
      return const UiKitView(
        viewType: IptvNative.viewType,
        creationParamsCodec: StandardMessageCodec(),
      );
    }
    return const ColoredBox(
      color: Colors.black,
      child: Center(child: Text('Video is available on Android and iOS only.', style: TextStyle(color: Colors.white70))),
    );
  }
}

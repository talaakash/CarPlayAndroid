import 'package:flutter_test/flutter_test.dart';

import 'package:car_play/main.dart';

void main() {
  // The original counter demo was removed from the Home Screen, so this checks that the app
  // starts and shows the Home Screen (IPTV is unavailable without native plugins in tests).
  testWidgets('App starts and shows the Home Screen', (WidgetTester tester) async {
    await tester.pumpWidget(const MyApp());
    await tester.pump();
    expect(find.text('Car Play'), findsOneWidget);
    expect(find.textContaining('IPTV is starting or not available'), findsOneWidget);
  });
}

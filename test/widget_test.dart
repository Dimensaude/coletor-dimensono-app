import 'package:flutter_test/flutter_test.dart';

import 'package:bandsono/app_state.dart';

void main() {
  test('relativeTime handles the empty case', () {
    expect(relativeTime(0), 'nunca');
  });
}

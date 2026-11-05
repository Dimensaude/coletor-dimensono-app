import 'package:bandsono/app_state.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('o nome da pulseira aparece como Actband, igual ao painel', () {
    expect(marcaPulseira('HUAWEI Band 10-61A'), 'Actband-61A');
    expect(marcaPulseira('HUAWEI Band 10-906'), 'Actband-906');
    expect(marcaPulseira('huawei band 10'), 'Actband');
    expect(marcaPulseira('HUAWEI WATCH FIT 3'), 'Actband WATCH FIT 3');
    expect(marcaPulseira('HONOR Band 9'), 'HONOR Band 9');
    expect(AppState().deviceName, 'Actband');
  });
}

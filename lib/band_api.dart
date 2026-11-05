import 'package:flutter/services.dart';

/// Thin wrapper over the native channels. The Coletor app only needs connection,
/// participant code, consent and export; no data reading.
Map<String, dynamic> _map(dynamic v) => v is Map ? Map<String, dynamic>.from(v) : <String, dynamic>{};

class BandApi {
  static const MethodChannel _m = MethodChannel('bandsono/band');
  static const EventChannel _status = EventChannel('bandsono/status');
  static const EventChannel _scan = EventChannel('bandsono/scan');

  static Stream<Map<String, dynamic>>? _statusStream;

  static Stream<Map<String, dynamic>> statusStream() {
    _statusStream ??= _status.receiveBroadcastStream().map(_map).asBroadcastStream();
    return _statusStream!;
  }

  static Stream<Map<String, dynamic>> scanStream() => _scan.receiveBroadcastStream().map(_map);

  static Future<Map<String, dynamic>> status() async => _map(await _m.invokeMethod('status'));

  // permissions & bluetooth
  static Future<bool> permissionsGranted() async => (await _m.invokeMethod<bool>('permissionsGranted')) ?? false;
  static Future<bool> requestPermissions() async => (await _m.invokeMethod<bool>('requestPermissions')) ?? false;
  static Future<bool> bluetoothEnabled() async => (await _m.invokeMethod<bool>('bluetoothEnabled')) ?? false;
  static Future<void> enableBluetooth() async => _m.invokeMethod('enableBluetooth');

  // pairing & scan
  static Future<bool> startScan() async => (await _m.invokeMethod<bool>('startScan')) ?? false;
  static Future<void> stopScan() async => _m.invokeMethod('stopScan');
  static Future<void> pair(String address, String name) async => _m.invokeMethod('pair', {'address': address, 'name': name});
  static Future<void> forget() async => _m.invokeMethod('forget');

  // service & sync
  static Future<void> startService() async => _m.invokeMethod('startService');
  static Future<void> syncNow() async => _m.invokeMethod('syncNow');
  static Future<void> reconnect() async => _m.invokeMethod('reconnect');

  // participant code & consent
  static Future<bool> setParticipantCode(String code) async =>
      (await _m.invokeMethod<bool>('setParticipantCode', {'code': code})) ?? false;
  static Future<bool> setConsent(bool value) async =>
      (await _m.invokeMethod<bool>('setConsent', {'value': value})) ?? false;

  /// The server no longer has a participant code for this account: stops uploading until it is released again.
  static Future<bool> unlinkParticipant() async => (await _m.invokeMethod<bool>('unlinkParticipant')) ?? false;

  static Future<bool> confirmParticipantChange() async =>
      (await _m.invokeMethod<bool>('confirmParticipantChange')) ?? false;
  static Future<bool> rejectParticipantChange() async =>
      (await _m.invokeMethod<bool>('rejectParticipantChange')) ?? false;

  // Dimensono session (private native file bandsono_conta); the token never goes through the settings channel
  static Future<Map<String, dynamic>> accountLoad() async => _map(await _m.invokeMethod('accountLoad'));
  static Future<void> accountSave(String token, String userId, String phone) async =>
      _m.invokeMethod('accountSave', {'token': token, 'userId': userId, 'phone': phone});
  static Future<void> accountExpired(bool value) async => _m.invokeMethod('accountExpired', {'value': value});
  static Future<void> accountClear() async => _m.invokeMethod('accountClear');
  static Future<void> clearProfessionalPrefill() async => _m.invokeMethod('clearProfessionalPrefill');

  /// Optional address of the Dimensono API (coletor.properties); empty when not set.
  static Future<String> apiBaseUrl() async => (await _m.invokeMethod<String>('apiBaseUrl')) ?? '';

  /// Opens a Dimensono page in the browser (native allows only https://dimensono.com.br/).
  static Future<bool> openUrl(String url) async => (await _m.invokeMethod<bool>('openUrl', {'url': url})) ?? false;

  /// Erases the local database, settings and session (after the account was deleted).
  static Future<void> wipeAll() async => _m.invokeMethod('wipeAll');

  /// Exports the stored data as CSV files (whole period; the native side takes unix seconds).
  static Future<void> exportCsv() async {
    final to = DateTime.now().millisecondsSinceEpoch ~/ 1000 + 86400;
    await _m.invokeMethod('exportCsv', {'from': 0, 'to': to});
  }

  // battery optimisation
  static Future<bool> batteryOptimizationIgnored() async =>
      (await _m.invokeMethod<bool>('batteryOptimizationIgnored')) ?? true;
  static Future<void> requestIgnoreBatteryOptimization() async => _m.invokeMethod('requestIgnoreBatteryOptimization');
}

import 'dart:async';

import 'package:flutter/foundation.dart';

import 'band_api.dart';
import 'dimensono_api.dart';

/// Version of the consent text sent to the server with the link.
const String kConsentVersion = 'coletor-1.7.0';

/// Holds the live band status. No analysis, no data reading.
class AppState extends ChangeNotifier {
  Map<String, dynamic> status = <String, dynamic>{};

  final DimensonoApi api;

  /// Why the upload is waiting for release (server answer), empty for the generic wait.
  String linkProblem = '';
  DateTime _lastLinkTry = DateTime.fromMillisecondsSinceEpoch(0);
  bool _refreshing = false;
  bool _handlingDeleted = false;

  /// User id of the stored Dimensono session (empty on old installs): "Entrar novamente" must be the same person.
  String storedUserId = '';

  AppState({DimensonoApi? api}) : api = api ?? HttpDimensonApi();

  StreamSubscription<Map<String, dynamic>>? _sub;
  Timer? _clock;
  bool _initialized = false;

  Future<void> init() async {
    if (_initialized) return;
    _initialized = true;
    try {
      status = await BandApi.status();
    } catch (e) {
      debugPrint('init: $e');
    }
    await _loadAccount();
    await _handleDeleted();
    _sub = BandApi.statusStream().listen(_onStatus, onError: (Object e) => debugPrint('status: $e'));
    try {
      await BandApi.startService();
    } catch (e) {
      debugPrint('startService: $e');
    }
    _clock = Timer.periodic(const Duration(minutes: 1), (_) {
      notifyListeners();
      // Waiting for the release of the upload: try again every 5 minutes while the app is open
      if (awaitingRelease && DateTime.now().difference(_lastLinkTry) >= const Duration(minutes: 5)) refreshLink();
    });
    refreshLink();
  }

  Future<void> _loadAccount() async {
    try {
      final base = await BandApi.apiBaseUrl();
      final a = api;
      if (base.isNotEmpty && a is HttpDimensonApi) a.baseUrl = base.replaceAll(RegExp(r'/+$'), '');
      final acc = await BandApi.accountLoad();
      final t = (acc['token'] as String?) ?? '';
      api.token = t.isEmpty ? null : t;
      storedUserId = (acc['userId'] as String?) ?? '';
    } catch (e) {
      debugPrint('account: $e');
    }
  }

  @override
  void dispose() {
    _sub?.cancel();
    _clock?.cancel();
    super.dispose();
  }

  void _onStatus(Map<String, dynamic> s) {
    status = s;
    notifyListeners();
    _handleDeleted();
  }

  /// The study server erased this participant (410): the account is gone, so the session goes too and the
  /// entry flow comes back. Local data stays on the phone.
  Future<void> _handleDeleted() async {
    if (!participantDeleted || !hasSession || _handlingDeleted) return;
    _handlingDeleted = true;
    try {
      await clearSession();
    } finally {
      _handlingDeleted = false;
    }
  }

  Future<void> reloadStatus() async {
    try {
      status = await BandApi.status();
    } catch (e) {
      debugPrint('reloadStatus: $e');
    }
    notifyListeners();
    await _handleDeleted();
  }

  // ---- band status ----
  String get state => (status['state'] as String?) ?? 'DISCONNECTED';
  bool get paired => ((status['address'] as String?) ?? '').isNotEmpty;
  bool get ready => state == 'READY';
  bool get syncing => status['syncing'] == true || state == 'SYNCING';
  int get battery => (status['battery'] as num?)?.toInt() ?? -1;
  int get lastSync => (status['lastSync'] as num?)?.toInt() ?? 0;
  bool get lastSyncOk => status['lastSyncOk'] != false;
  bool get linkStuck => status['linkStuck'] == true;
  String get address => (status['address'] as String?) ?? '';
  String get firmware => (status['firmware'] as String?) ?? '';
  String get deviceName {
    final n = (status['name'] as String?) ?? '';
    return n.isEmpty ? 'Actband' : marcaPulseira(n);
  }

  // ---- participant & consent ----
  String get participant => (status['participant'] as String?) ?? '';
  bool get participantLinked => status['participantLinked'] == true;
  bool get consent => status['consent'] == true;

  /// The server erased this participant (410 participant_deleted): uploads are off and the entry flow is back.
  bool get participantDeleted => status['participantDeleted'] == true;

  /// The participant already answered the consent question at least once (old installs report true).
  bool get consentAsked => status['consentAsked'] == true;

  /// A link arrived with a code different from the linked one; empty when nothing is pending.
  String get pendingParticipant => (status['pendingParticipant'] as String?) ?? '';

  /// Friendly error from the last link, empty when ok.
  String get linkError => (status['linkError'] as String?) ?? '';
  bool get calibrating => status['calibrating'] == true;

  // ---- Dimensono session ----
  bool get hasSession => status['hasSession'] == true;
  bool get sessionExpired => hasSession && status['sessionExpired'] == true;
  String get accountPhone => (status['accountPhone'] as String?) ?? '';

  /// Professional code (DMS-...) that came from a /actigrafia/p/ link; empty when none.
  String get professionalPrefill => (status['professionalPrefill'] as String?) ?? '';

  /// The entry flow (phone, professional, data, consent) is shown on installs without a linked code and on
  /// accounts that did not finish it. Turning the upload off later does not bring it back.
  bool get needsEntry => participantDeleted || (hasSession ? !consentAsked : !participantLinked);

  /// Install from 1.5/1.6 linked by a link, without an account, that has not answered the consent yet:
  /// keeps the old consent card instead of the new flow.
  bool get needsLegacyConsent => !hasSession && participantLinked && !consentAsked;

  /// Account finished the flow but the server has not released the participant code yet: nothing is uploaded.
  bool get awaitingRelease => !participantDeleted && hasSession && consentAsked && !participantLinked;

  /// Stores the Dimensono session in the private native file.
  Future<void> saveSession(String token, String userId, String phone) async {
    api.token = token;
    // The password login does not return the user id: keep the one already stored
    final id = userId.isEmpty ? storedUserId : userId;
    storedUserId = id;
    await BandApi.accountSave(token, id, phone);
    final digits = phone.replaceAll(RegExp(r'[^0-9]'), '');
    status = {
      ...status,
      'hasSession': true,
      'sessionExpired': false,
      'accountPhone': digits.length > 4 ? digits.substring(digits.length - 4) : digits,
    };
    notifyListeners();
  }

  /// A 401 anywhere: asks to sign in again, but collection and upload go on with the current code.
  Future<void> markSessionExpired() async {
    if (sessionExpired) return;
    status = {...status, 'sessionExpired': true};
    notifyListeners();
    try {
      await BandApi.accountExpired(true);
    } catch (e) {
      debugPrint('markSessionExpired: $e');
    }
  }

  /// Drops the session (professional account, or the user chose another number).
  Future<void> clearSession() async {
    api.token = null;
    storedUserId = '';
    try {
      await BandApi.accountClear();
    } catch (e) {
      debugPrint('clearSession: $e');
    }
    status = {...status, 'hasSession': false, 'sessionExpired': false, 'accountPhone': ''};
    notifyListeners();
  }

  Future<void> consumeProfessionalPrefill() async {
    status = {...status, 'professionalPrefill': ''};
    try {
      await BandApi.clearProfessionalPrefill();
    } catch (e) {
      debugPrint('prefill: $e');
    }
  }

  /// Finishes the entry flow: asks the server for the participant code (the patient phone), applies it and
  /// records the consent. Without the code (route not live yet, network) nothing is sent: the app keeps
  /// collecting and retries later.
  Future<void> completeEntry() async {
    await _adoptServerCode(post: true);
    await setConsent(true);
  }

  /// On every app open and when the user asks: reads the code from the server and adopts it if it changed
  /// (a manual re-link by the professional), or asks for it while waiting for the release.
  Future<void> refreshLink() async {
    if (_refreshing || !hasSession || api.token == null || !consentAsked) return;
    await _adoptServerCode(post: !participantLinked);
  }

  Future<void> _adoptServerCode({required bool post}) async {
    if (_refreshing) return;
    _refreshing = true;
    _lastLinkTry = DateTime.now();
    try {
      var code = (await api.getLink()).participantCode;
      var again = post;
      if (code == null && participantLinked) {
        // The professional removed the code on the server: stop uploading under the old one right away
        if (await BandApi.unlinkParticipant()) {
          status = {...status, 'participantLinked': false};
          again = true; // the patient may still be linked to the professional: ask for the code again (409 not_linked = wait)
        }
      }
      if (code == null && again) code = (await api.postLink(consentVersion: kConsentVersion)).participantCode;
      linkProblem = '';
      if (code != null && (code != participant || !participantLinked)) {
        if (await BandApi.setParticipantCode(code)) {
          status = {...status, 'participant': code, 'participantLinked': true};
        }
      }
    } on ApiError catch (e) {
      if (e.unauthorized) {
        await markSessionExpired();
      } else {
        linkProblem = _linkProblemText(e);
      }
    } catch (e) {
      debugPrint('link: $e');
    } finally {
      _refreshing = false;
      notifyListeners();
    }
  }

  static String _linkProblemText(ApiError e) {
    switch (e.code) {
      case 'code_taken':
        return 'Este número já está vinculado a outro paciente. Fale com o profissional.';
      case 'code_incompatible':
      case 'phone_missing':
        return 'Não foi possível liberar o envio para este número. Fale com o profissional.';
      case 'not_linked':
        return 'Falta vincular o profissional.';
      default:
        return '';
    }
  }

  /// Deletes the account on the server and then the data on this phone.
  /// Returns ok, expired (sign in again), doctor (professional account) or error.
  Future<String> deleteAccount() async {
    // Uploading goes off FIRST: a running upload would recreate data on the server after the deletion
    final hadConsent = consent;
    if (hadConsent) {
      try {
        if (!await setConsent(false)) return 'error';
      } catch (e) {
        debugPrint('setConsent(false): $e');
        return 'error';
      }
    }
    Future<void> restoreConsent() async {
      if (!hadConsent) return;
      try {
        await setConsent(true);
      } catch (e) {
        debugPrint('restore consent: $e');
      }
    }

    try {
      await api.deleteMe();
    } on ApiError catch (e) {
      if (e.status == 409 && e.code == 'account_already_deleted') {
        // already gone on the server: just clean the phone
      } else if (e.unauthorized) {
        await restoreConsent();
        await markSessionExpired();
        return 'expired';
      } else if (e.status == 403) {
        await restoreConsent();
        return 'doctor';
      } else {
        await restoreConsent();
        return 'error';
      }
    } catch (_) {
      await restoreConsent();
      return 'error';
    }
    try {
      await BandApi.wipeAll();
    } catch (e) {
      // The account is gone on the server but this phone still holds data: say so, never pretend it worked
      debugPrint('wipeAll: $e');
      return 'error';
    }
    api.token = null;
    storedUserId = '';
    linkProblem = '';
    await reloadStatus();
    // Whatever the native side still reports, the phone is now a fresh install
    status = {
      ...status,
      'hasSession': false,
      'sessionExpired': false,
      'participantLinked': false,
      'participantDeleted': false,
      'consent': false,
      'consentAsked': false,
      'address': '',
    };
    notifyListeners();
    return 'ok';
  }

  // ---- study-server upload ----
  bool get sbUploading => status['sbUploading'] == true;
  bool get sbLastUploadOk => status['sbLastUploadOk'] != false;
  int get sbPending => (status['sbPending'] as num?)?.toInt() ?? -1;

  /// Applies the pending code (a different link arrived).
  Future<bool> confirmParticipantChange() async {
    final ok = await BandApi.confirmParticipantChange();
    if (ok) await reloadStatus();
    return ok;
  }

  /// Discards the pending code and keeps the current link.
  Future<bool> rejectParticipantChange() async {
    final ok = await BandApi.rejectParticipantChange();
    if (ok) {
      status = {...status, 'pendingParticipant': ''};
      notifyListeners();
    }
    return ok;
  }

  Future<bool> setConsent(bool value) async {
    final ok = await BandApi.setConsent(value);
    if (ok) {
      status = {...status, 'consent': value, 'consentAsked': true};
      notifyListeners();
    }
    return ok;
  }
}

/// Nome da pulseira para exibição, igual ao painel (nome_actband em actigrafia-server/app/relogios.py):
/// "HUAWEI Band 10-61A" vira "Actband-61A". Outro aparelho da marca na busca também perde o nome do
/// fabricante. O pareamento usa o nome original.
String marcaPulseira(String name) => name
    .replaceAll(RegExp(r'huawei\s*band\s*\d*\s*', caseSensitive: false), 'Actband')
    .replaceAll(RegExp(r'huawei', caseSensitive: false), 'Actband')
    .trim();

String relativeTime(int millis) {
  if (millis <= 0) return 'nunca';
  final d = DateTime.fromMillisecondsSinceEpoch(millis);
  final now = DateTime.now();
  final diff = now.difference(d);
  final hm = '${_two(d.hour)}:${_two(d.minute)}';
  if (diff.inMinutes < 1) return 'agora';
  if (diff.inMinutes < 60) return 'há ${diff.inMinutes} min';
  final sameDay = d.year == now.year && d.month == now.month && d.day == now.day;
  if (sameDay) return 'hoje às $hm';
  final y = now.subtract(const Duration(days: 1));
  if (d.year == y.year && d.month == y.month && d.day == y.day) return 'ontem às $hm';
  return '${_two(d.day)}/${_two(d.month)} às $hm';
}

String _two(int n) => n < 10 ? '0$n' : '$n';

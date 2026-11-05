import 'dart:async';
import 'dart:convert';
import 'dart:io';

/// Endereço da API do Dimensono. Pode ser trocado em teste/desenvolvimento pela chave nativa
/// `dimensono_api_url` do arquivo coletor.properties (ver BandApi.apiBaseUrl).
const String kDimensonoApiUrl = 'https://dimensono.com.br/api';

/// Erro tipado de uma chamada. [status] 0 = sem rede/tempo esgotado.
class ApiError implements Exception {
  final int status;

  /// Código curto do servidor (ex.: unknown_code, password_required) ou texto do detalhe.
  final String code;
  final List<String> errors;

  const ApiError(this.status, this.code, {this.errors = const []});

  bool get isNetwork => status == 0;
  bool get unauthorized => status == 401;
  bool get notFound => status == 404;
  bool get tooMany => status == 429;

  @override
  String toString() => 'ApiError($status, $code)';
}

class StartResult {
  final String challengeId;
  final String? debugCode;
  const StartResult(this.challengeId, this.debugCode);
}

class VerifyResult {
  final String token;
  final String accountState;
  final String next;
  final String userId;
  const VerifyResult(this.token, this.accountState, this.next, this.userId);
}

class Professional {
  final String doctorName;
  final String professionalName;
  final String clinicName;
  const Professional(this.doctorName, this.professionalName, this.clinicName);

  /// Nome para mostrar: o nome profissional quando houver.
  String get display => professionalName.isNotEmpty ? professionalName : doctorName;
}

class ReferralResult {
  final String status;
  final String next;
  const ReferralResult(this.status, this.next);
}

class PatientMe {
  final String name;
  final String birthDate;
  final String sex;
  final Professional? doctor;
  final String next;
  const PatientMe(this.name, this.birthDate, this.sex, this.doctor, this.next);
}

class ActigraphyLink {
  final String? participantCode;
  const ActigraphyLink(this.participantCode);
}

/// Cliente da API do Dimensono (só o que o app precisa). O [token] é do paciente.
abstract class DimensonoApi {
  String? token;

  Future<StartResult> start(String phone);
  Future<VerifyResult> verify(String challengeId, String code);

  /// Login com senha (telefone de profissional). Devolve o token.
  Future<String> login(String phone, String password);
  Future<Professional> preview(String code);
  Future<ReferralResult> referral(String code);
  Future<PatientMe> me();

  /// Devolve o próximo passo.
  Future<String> registration(String name, String birthIso, String sex);
  Future<ActigraphyLink> getLink();
  Future<ActigraphyLink> postLink({String? consentVersion});
  Future<void> deleteMe();
}

class HttpDimensonApi extends DimensonoApi {
  String baseUrl;
  final Duration timeout;

  HttpDimensonApi({String? baseUrl, this.timeout = const Duration(seconds: 20)})
      : baseUrl = (baseUrl == null || baseUrl.isEmpty ? kDimensonoApiUrl : baseUrl).replaceAll(RegExp(r'/+$'), '');

  Future<Map<String, dynamic>> _call(String method, String path, {Map<String, dynamic>? body, bool auth = true}) async {
    final client = HttpClient()..connectionTimeout = timeout;
    try {
      final req = await client.openUrl(method, Uri.parse('$baseUrl$path')).timeout(timeout);
      req.headers.set(HttpHeaders.acceptHeader, 'application/json');
      if (auth && token != null) req.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
      if (body != null) {
        req.headers.contentType = ContentType.json;
        req.add(utf8.encode(jsonEncode(body)));
      }
      final res = await req.close().timeout(timeout);
      final text = await res.transform(utf8.decoder).join().timeout(timeout);
      Object? json;
      try {
        json = text.isEmpty ? null : jsonDecode(text);
      } catch (_) {
        json = null;
      }
      if (res.statusCode >= 200 && res.statusCode < 300) {
        return json is Map ? Map<String, dynamic>.from(json) : <String, dynamic>{};
      }
      throw _error(res.statusCode, json);
    } on ApiError {
      rethrow;
    } on TimeoutException {
      throw const ApiError(0, 'timeout');
    } on SocketException {
      throw const ApiError(0, 'network');
    } on HandshakeException {
      throw const ApiError(0, 'network');
    } on HttpException {
      throw const ApiError(0, 'network');
    } finally {
      client.close(force: true);
    }
  }

  static ApiError _error(int status, Object? json) {
    var code = '';
    var errors = <String>[];
    if (json is Map) {
      final d = json['detail'];
      if (d is String) {
        code = d;
      } else if (d is Map) {
        code = (d['code'] ?? d['message'] ?? '').toString();
        final e = d['errors'];
        if (e is List) errors = e.map((x) => x.toString()).toList();
      } else if (d is List) {
        // erro de validação do FastAPI
        errors = d.map((x) => x is Map ? (x['msg'] ?? '').toString() : x.toString()).toList();
      }
    }
    return ApiError(status, code, errors: errors);
  }

  static String _s(Object? v) => v == null ? '' : v.toString();

  @override
  Future<StartResult> start(String phone) async {
    final j = await _call('POST', '/auth/app/start', body: {'phone': phone}, auth: false);
    final dc = j['debug_code'];
    return StartResult(_s(j['challenge_id']), dc == null ? null : _s(dc));
  }

  @override
  Future<VerifyResult> verify(String challengeId, String code) async {
    final j = await _call('POST', '/auth/app/verify', body: {'challenge_id': challengeId, 'code': code}, auth: false);
    return VerifyResult(_s(j['access_token']), _s(j['account_state']), _s(j['next']), _s(j['user_id']));
  }

  @override
  Future<String> login(String phone, String password) async {
    final j = await _call('POST', '/auth/login', body: {'identifier': phone, 'password': password}, auth: false);
    return _s(j['access_token']);
  }

  static Professional _prof(Object? v) {
    final m = v is Map ? v : const {};
    return Professional(_s(m['doctor_name'] ?? m['name']), _s(m['professional_name']), _s(m['clinic_name']));
  }

  @override
  Future<Professional> preview(String code) async =>
      _prof(await _call('GET', '/patient/referral/${Uri.encodeComponent(code)}/preview'));

  @override
  Future<ReferralResult> referral(String code) async {
    final j = await _call('POST', '/patient/referral', body: {'code': code});
    return ReferralResult(_s(j['status']), _s(j['next']));
  }

  @override
  Future<PatientMe> me() async {
    final j = await _call('GET', '/patient/me');
    final p = j['patient'] is Map ? j['patient'] as Map : const {};
    final d = j['doctor'];
    return PatientMe(_s(p['name']), _s(p['birth_date']), _s(p['biological_sex']), d is Map ? _prof(d) : null, _s(j['next']));
  }

  @override
  Future<String> registration(String name, String birthIso, String sex) async {
    final j = await _call('POST', '/patient/registration',
        body: {'name': name, 'birth_date': birthIso, 'biological_sex': sex});
    return _s(j['next']);
  }

  @override
  Future<ActigraphyLink> getLink() async {
    final j = await _call('GET', '/patient/actigraphy/link');
    final c = j['participant_code'];
    return ActigraphyLink(c == null || _s(c).isEmpty ? null : _s(c));
  }

  @override
  Future<ActigraphyLink> postLink({String? consentVersion}) async {
    final j = await _call('POST', '/patient/actigraphy/link',
        body: consentVersion == null ? <String, dynamic>{} : {'consent_version': consentVersion});
    final c = j['participant_code'];
    return ActigraphyLink(c == null || _s(c).isEmpty ? null : _s(c));
  }

  @override
  Future<void> deleteMe() async {
    await _call('DELETE', '/patient/me', body: {'confirmation': 'EXCLUIR'});
  }
}

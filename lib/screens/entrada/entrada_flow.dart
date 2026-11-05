import 'package:flutter/material.dart';

import '../../app_state.dart';
import '../../dimensono_api.dart';
import 'passos.dart';

enum Passo { carregando, telefone, codigo, senha, profissional, confirma, vinculado, dados, consentimento }

/// Signing in again must be the same person that owns the stored session (and the data on this phone).
String _msgOutroNumero(String suffix) =>
    suffix.isEmpty ? 'Entre com o mesmo número usado antes neste celular.' : 'Entre com o número de final $suffix.';

const String _msgProfissional = 'Esta conta é de profissional; use o app com o telefone de um paciente.';

/// New-install entry: phone, WhatsApp code, professional code, confirmation, patient data and consent.
/// The server decides each next step (`next`); the app only shows what is missing.
class EntradaFlow extends StatefulWidget {
  final AppState app;

  /// Called when the flow ends on its own (used when it runs as a screen to sign in again).
  final VoidCallback? onDone;

  const EntradaFlow({super.key, required this.app, this.onDone});

  @override
  State<EntradaFlow> createState() => _EntradaFlowState();
}

class _EntradaFlowState extends State<EntradaFlow> {
  AppState get app => widget.app;
  DimensonoApi get api => app.api;

  Passo passo = Passo.telefone;
  bool busy = false;
  String? erro;
  List<String> erros = const [];
  String? aviso;

  String phone = '';
  String challengeId = '';
  String? debugCode;
  String profCode = '';
  Professional? prof;
  PatientMe? me;
  String proximo = '';

  @override
  void initState() {
    super.initState();
    if (app.hasSession && !app.sessionExpired && api.token != null) {
      passo = Passo.carregando;
      WidgetsBinding.instance.addPostFrameCallback((_) => _resolver());
    }
  }

  void _ir(Passo p, {String? msg}) {
    if (!mounted) return;
    setState(() {
      passo = p;
      busy = false;
      erro = msg;
      erros = const [];
    });
  }

  /// Runs a step with the busy state and maps the typed errors to friendly messages.
  Future<void> _run(Future<void> Function() body, {String? Function(ApiError e)? onApi}) async {
    setState(() {
      busy = true;
      erro = null;
      erros = const [];
    });
    try {
      await body();
    } on ApiError catch (e) {
      if (!mounted) return;
      final custom = onApi?.call(e);
      if (custom != null) {
        // an empty text means the handler already moved on (or set the state itself)
        if (custom.isNotEmpty) {
          setState(() {
            busy = false;
            erro = custom;
          });
        }
        return;
      }
      if (e.unauthorized) {
        await app.markSessionExpired();
        _ir(Passo.telefone, msg: 'Sua sessão expirou. Confirme o número de novo.');
        return;
      }
      setState(() {
        busy = false;
        erro = _mensagemGeral(e);
      });
      return;
    } catch (_) {
      if (!mounted) return;
      setState(() {
        busy = false;
        erro = 'Algo deu errado. Tente de novo.';
      });
      return;
    }
    if (mounted && busy) setState(() => busy = false);
  }

  String _mensagemGeral(ApiError e) {
    if (e.isNetwork) return 'Sem conexão com a internet. Confira e tente de novo.';
    if (e.status == 403 && e.code == 'patient_only') return _msgProfissional;
    if (e.tooMany) return 'Muitas tentativas. Aguarde um pouco e tente de novo.';
    if (e.status >= 500) return 'O serviço está indisponível agora. Tente de novo em instantes.';
    return 'Algo deu errado. Tente de novo.';
  }

  // ------------------------------------------------------------------ steps

  Future<void> _enviarTelefone(String digits) => _run(() async {
        final r = await api.start(digits);
        phone = digits;
        challengeId = r.challengeId;
        debugCode = r.debugCode;
        _ir(Passo.codigo);
      }, onApi: (e) {
        if (e.status == 400) return 'Telefone inválido. Confira o número com DDD.';
        if (e.tooMany) return 'Você pediu códigos demais. Tente de novo mais tarde.';
        return null;
      });

  Future<void> _confirmarCodigo(String code) => _run(() async {
        final v = await api.verify(challengeId, code);
        if (_outraPessoa(v.userId)) return;
        await app.saveSession(v.token, v.userId, phone);
        await _depoisDoLogin(v.next);
      }, onApi: (e) {
        if (e.status == 409 && e.code == 'password_required') {
          _ir(Passo.senha);
          return '';
        }
        if (e.status == 400) return 'Código inválido ou expirado. Confira ou peça um novo código.';
        if (e.tooMany) return 'Muitas tentativas. Peça um novo código.';
        return null;
      });

  Future<void> _entrarComSenha(String senha) => _run(() async {
        final t = await api.login(phone, senha);
        // The login does not return the user id: the last 4 digits of the stored phone are the check
        if (_outraPessoa('')) return;
        await app.saveSession(t, '', phone);
        await _depoisDoLogin(null);
      }, onApi: (e) {
        if (e.status == 401) return 'Senha incorreta.';
        if (e.tooMany) return 'Muitas tentativas. Aguarde um pouco e tente de novo.';
        return null;
      });

  /// Re-entry (a session is stored): a different user than the stored one is refused and the new session is
  /// discarded (it is never saved), so the data of this phone is not uploaded under someone else's code.
  bool _outraPessoa(String newUserId) {
    if (!app.hasSession) return false;
    final stored = app.storedUserId;
    final digits = phone.replaceAll(RegExp(r'[^0-9]'), '');
    final suffix = app.accountPhone;
    final differentId = stored.isNotEmpty && newUserId.isNotEmpty && stored != newUserId;
    final differentPhone = newUserId.isEmpty && suffix.isNotEmpty && !digits.endsWith(suffix);
    if (!differentId && !differentPhone) return false;
    _ir(Passo.telefone, msg: _msgOutroNumero(suffix));
    return true;
  }

  Future<void> _depoisDoLogin(String? next) async {
    if (next == 'link') {
      _ir(Passo.profissional);
      return;
    }
    await _resolverInterno();
  }

  /// With a session already stored (app reopened mid-flow, or after the login): asks the server what is missing.
  Future<void> _resolver() => _run(_resolverInterno, onApi: (e) {
        if (e.status == 403 && e.code == 'patient_only') {
          app.clearSession();
          _ir(Passo.telefone, msg: _msgProfissional);
          return '';
        }
        if (e.unauthorized) return null;
        return _mensagemGeral(e);
      });

  Future<void> _resolverInterno() async {
    try {
      me = await api.me();
    } on ApiError catch (e) {
      if (e.status == 403 && e.code == 'patient_only') {
        await app.clearSession();
        _ir(Passo.telefone, msg: _msgProfissional);
        return;
      }
      rethrow;
    }
    final m = me!;
    proximo = m.next;
    // Signing in again on a phone that already finished the flow: nothing more to ask
    if (app.participantLinked && app.consentAsked) {
      widget.onDone?.call();
      return;
    }
    if (m.doctor == null || m.next == 'link') {
      _ir(Passo.profissional);
    } else {
      prof = m.doctor;
      _ir(Passo.vinculado);
    }
  }

  Future<void> _buscarProfissional(String code) => _run(() async {
        prof = await api.preview(code);
        profCode = code;
        await app.consumeProfessionalPrefill();
        _ir(Passo.confirma);
      }, onApi: (e) {
        if (e.status == 404) return 'Não encontramos esse código. Confira com o profissional.';
        if (e.status == 409) return 'Este profissional não está disponível no momento.';
        return null;
      });

  Future<void> _vincular() => _run(() async {
        final r = await api.referral(profCode);
        if (r.status == 'transfer_pending') {
          aviso = 'Pedido de troca enviado; aguardando liberação. Por enquanto você continua com o profissional atual.';
        }
        await _seguir(r.next);
      }, onApi: (e) {
        if (e.status == 404) return 'Não encontramos esse código. Confira com o profissional.';
        if (e.status == 409) return 'Este profissional não está disponível no momento.';
        return null;
      });

  Future<void> _seguir(String next) async {
    proximo = next;
    if (next == 'link') {
      _ir(Passo.profissional, msg: 'Não foi possível vincular. Confira o código.');
      return;
    }
    if (next == 'registration') {
      // Best effort: shows what the account already has
      try {
        me = await api.me();
      } catch (_) {}
      _ir(Passo.dados);
    } else {
      _ir(Passo.consentimento);
    }
  }

  Future<void> _enviarDados(DadosPaciente d) => _run(() async {
        final next = await api.registration(d.nome, d.nascimentoIso, d.sexo);
        proximo = next;
        _ir(Passo.consentimento);
      }, onApi: (e) {
        if (e.status == 409 && e.code == 'not_linked') {
          _ir(Passo.profissional, msg: 'Falta vincular o profissional.');
          return '';
        }
        if (e.status == 400 && e.errors.isNotEmpty) {
          setState(() {
            busy = false;
            erros = e.errors.map(_limpaErro).toList();
          });
          return '';
        }
        return null;
      });

  static String _limpaErro(String s) {
    final i = s.indexOf(': ');
    return i > 0 && i < 24 && !s.substring(0, i).contains(' ') ? s.substring(i + 2) : s;
  }

  Future<void> _concluir() async {
    setState(() => busy = true);
    try {
      await app.completeEntry();
    } catch (_) {
      // the flow never blocks here: without the code the app only collects
    }
    if (!mounted) return;
    setState(() => busy = false);
    widget.onDone?.call();
  }

  // ------------------------------------------------------------------ build

  @override
  Widget build(BuildContext context) {
    switch (passo) {
      case Passo.carregando:
        return Column(children: [
          const SizedBox(height: 60),
          if (erro == null) const CircularProgressIndicator() else ...[
            PassoErro(erro),
            TextButton(onPressed: _resolver, child: const Text('Tentar de novo')),
            TextButton(
              onPressed: () async {
                await app.clearSession();
                _ir(Passo.telefone);
              },
              child: const Text('Usar outro número'),
            ),
          ],
        ]);
      case Passo.telefone:
        return PassoTelefone(
          key: const ValueKey('telefone'),
          inicial: phone,
          busy: busy,
          erro: erro,
          aviso: app.participantDeleted
              ? 'Esta conta foi excluída e o envio dos dados foi interrompido. Os dados continuam guardados neste celular.'
              : app.sessionExpired
                  ? 'Sua sessão expirou. Entre de novo para continuar.'
                  : null,
          onSubmit: _enviarTelefone,
        );
      case Passo.codigo:
        return PassoCodigo(
          key: ValueKey('codigo-$challengeId'),
          telefone: phone,
          codigoAutomatico: debugCode,
          busy: busy,
          erro: erro,
          onSubmit: _confirmarCodigo,
          onVoltar: () => _ir(Passo.telefone),
          onReenviar: () => _enviarTelefone(phone),
        );
      case Passo.senha:
        return PassoSenha(busy: busy, erro: erro, onSubmit: _entrarComSenha, onVoltar: () => _ir(Passo.telefone));
      case Passo.profissional:
        return PassoProfissional(
          key: const ValueKey('profissional'),
          inicial: profCode.isNotEmpty ? profCode : app.professionalPrefill,
          busy: busy,
          erro: erro,
          aviso: app.linkError.isNotEmpty ? app.linkError : null,
          onSubmit: _buscarProfissional,
        );
      case Passo.confirma:
        return PassoConfirmaProfissional(
          prof: prof!,
          busy: busy,
          erro: erro,
          onSim: _vincular,
          onNao: () => _ir(Passo.profissional),
        );
      case Passo.vinculado:
        return PassoVinculado(
          prof: prof!,
          aviso: aviso,
          onContinuar: () => _run(() => _seguir(proximo.isEmpty ? 'home' : proximo)),
          onTrocar: () => _ir(Passo.profissional),
        );
      case Passo.dados:
        return PassoDados(
          nomeInicial: me?.name ?? '',
          nascimentoInicial: me?.birthDate ?? '',
          sexoInicial: me?.sex ?? '',
          busy: busy,
          erros: erros,
          aviso: aviso,
          onSubmit: _enviarDados,
        );
      case Passo.consentimento:
        return PassoConsentimento(busy: busy, aviso: aviso, onSubmit: _concluir);
    }
  }
}

/// Full-screen version, used by the "Entrar novamente" banner.
class EntradaTela extends StatelessWidget {
  final AppState app;

  const EntradaTela({super.key, required this.app});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Entrar novamente')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(22, 8, 22, 26),
          child: EntradaFlow(app: app, onDone: () => Navigator.of(context).maybePop()),
        ),
      ),
    );
  }
}

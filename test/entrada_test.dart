import 'dart:convert';
import 'dart:io';

import 'package:bandsono/app_state.dart';
import 'package:bandsono/dimensono_api.dart';
import 'package:bandsono/screens/entrada/passos.dart';
import 'package:bandsono/screens/main_screen.dart';
import 'package:bandsono/screens/privacy_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// Fake of the Dimensono API: records the calls and fails on demand.
class FakeApi extends DimensonoApi {
  final List<String> log = [];
  ApiError? startError, verifyError, loginError, previewError, referralError, meError, regError, getLinkError, postLinkError, deleteError;
  String? debugCode;
  VerifyResult verifyResult = const VerifyResult('tok-1', 'new', 'link', '7');
  Professional prof = const Professional('Ana Souza', 'Dra. Ana Souza', 'Clínica Sono Bom');
  ReferralResult referralResult = const ReferralResult('linked', 'registration');
  PatientMe meResult = const PatientMe('', '', '', null, 'link');
  String regNext = 'battery';
  String? serverCode = '5511999998888';
  String? existingCode;
  Map<String, dynamic>? lastRegistration;
  void Function()? onDelete;

  void _t(ApiError? e) {
    if (e != null) throw e;
  }

  @override
  Future<StartResult> start(String phone) async {
    log.add('start:$phone');
    _t(startError);
    return StartResult('chal-12345678', debugCode);
  }

  @override
  Future<VerifyResult> verify(String challengeId, String code) async {
    log.add('verify:$code');
    _t(verifyError);
    return verifyResult;
  }

  @override
  Future<String> login(String phone, String password) async {
    log.add('login:$phone');
    _t(loginError);
    return 'tok-login';
  }

  @override
  Future<Professional> preview(String code) async {
    log.add('preview:$code');
    _t(previewError);
    return prof;
  }

  @override
  Future<ReferralResult> referral(String code) async {
    log.add('referral:$code');
    _t(referralError);
    return referralResult;
  }

  @override
  Future<PatientMe> me() async {
    log.add('me');
    _t(meError);
    return meResult;
  }

  @override
  Future<String> registration(String name, String birthIso, String sex) async {
    log.add('registration');
    lastRegistration = {'name': name, 'birth_date': birthIso, 'biological_sex': sex};
    _t(regError);
    return regNext;
  }

  @override
  Future<ActigraphyLink> getLink() async {
    log.add('getLink');
    _t(getLinkError);
    return ActigraphyLink(existingCode);
  }

  @override
  Future<ActigraphyLink> postLink({String? consentVersion}) async {
    log.add('postLink');
    _t(postLinkError);
    return ActigraphyLink(serverCode);
  }

  @override
  Future<void> deleteMe() async {
    log.add('deleteMe');
    onDelete?.call();
    _t(deleteError);
  }
}

class TestState extends AppState {
  TestState(DimensonoApi api) : super(api: api);

  void push(Map<String, dynamic> s) {
    status = {...status, ...s};
    notifyListeners();
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late FakeApi api;
  late TestState app;
  late List<MethodCall> calls;
  late Map<String, dynamic> native;
  var wipeFails = false;

  List<String> methods() => calls.map((c) => c.method).toList();

  setUp(() {
    api = FakeApi();
    app = TestState(api);
    calls = [];
    native = {};
    wipeFails = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('bandsono/band'),
        (call) async {
      calls.add(call);
      final a = call.arguments is Map ? call.arguments as Map : const {};
      switch (call.method) {
        case 'status':
          return <String, dynamic>{...native};
        case 'setParticipantCode':
          native = {...native, 'participant': a['code'], 'participantLinked': true};
          return true;
        case 'setConsent':
          native = {...native, 'consent': a['value'], 'consentAsked': true};
          return true;
        case 'accountSave':
          native = {...native, 'hasSession': true};
          return true;
        case 'unlinkParticipant':
          native = {...native, 'participantLinked': false};
          return true;
        case 'wipeAll':
          if (wipeFails) throw PlatformException(code: 'io', message: 'disk');
          native = {};
          return null;
        case 'apiBaseUrl':
          return '';
      }
      return true;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('bandsono/band'), null);
    app.dispose();
  });

  Future<void> openMain(WidgetTester t) async {
    t.view.physicalSize = const Size(1080, 2200);
    t.view.devicePixelRatio = 3.0; // 360 x 733 logical
    addTearDown(t.view.reset);
    await t.pumpWidget(MaterialApp(home: Scaffold(body: SafeArea(child: MainScreen(app: app)))));
  }

  Future<void> tapBtn(WidgetTester t, String label) async {
    final f = find.widgetWithText(FilledButton, label);
    await t.ensureVisible(f);
    await t.tap(f);
    await t.pumpAndSettle();
  }

  Future<void> typeIn(WidgetTester t, int index, String text) async {
    final f = find.byType(TextField).at(index);
    await t.ensureVisible(f);
    await t.enterText(f, text);
    await t.pump();
  }

  Future<void> toCodeStep(WidgetTester t) async {
    await typeIn(t, 0, '(11) 91234-5678');
    await tapBtn(t, 'Enviar código');
  }

  Future<void> verifyCode(WidgetTester t) async {
    await typeIn(t, 0, '123456');
    await tapBtn(t, 'Confirmar');
  }

  Future<void> fillData(WidgetTester t, {String ano = '1990'}) async {
    await typeIn(t, 0, 'Maria da Silva');
    await typeIn(t, 1, '05');
    await typeIn(t, 2, '03');
    await typeIn(t, 3, ano);
    await t.ensureVisible(find.text('Feminino'));
    await t.tap(find.text('Feminino'));
    await t.pump();
    await tapBtn(t, 'Continuar');
  }

  Future<void> consent(WidgetTester t) async {
    await t.ensureVisible(find.byType(Checkbox));
    await t.tap(find.byType(Checkbox));
    await t.pump();
    await tapBtn(t, 'Concluir');
  }

  testWidgets('conta nova: telefone, codigo, profissional, confirmacao, dados e consentimento chegam na tela inicial', (t) async {
    await openMain(t);
    expect(find.text('Qual é o seu celular?'), findsOneWidget);
    expect(find.text('Antes de começar'), findsNothing);

    await toCodeStep(t);
    expect(api.log.first, 'start:11912345678');
    expect(find.textContaining('Não chegou? Confira o número'), findsOneWidget);

    await verifyCode(t);
    expect(api.log, contains('verify:123456'));
    expect(calls.where((c) => c.method == 'accountSave').single.arguments, {'token': 'tok-1', 'userId': '7', 'phone': '11912345678'});
    expect(api.token, 'tok-1');
    expect(find.text('Código do profissional'), findsWidgets);

    await typeIn(t, 0, ' dms-abcd1234 ');
    await tapBtn(t, 'Continuar');
    expect(api.log, contains('preview:DMS-ABCD1234'));
    expect(find.text('É este profissional?'), findsOneWidget);
    expect(find.text('Dra. Ana Souza'), findsOneWidget);
    expect(find.text('Clínica Sono Bom'), findsOneWidget);

    await tapBtn(t, 'Sim, é este');
    expect(api.log, contains('referral:DMS-ABCD1234'));
    expect(find.text('Seus dados'), findsOneWidget);

    await fillData(t);
    expect(api.lastRegistration, {'name': 'Maria da Silva', 'birth_date': '1990-03-05', 'biological_sex': 'F'});
    expect(find.text('Antes de começar'), findsOneWidget);
    expect(find.textContaining('profissional de saúde vinculado pelo código DMS'), findsOneWidget);
    expect(find.textContaining('ECG e arritmia, em formato bruto'), findsOneWidget);
    expect(find.textContaining('trajeto de GPS'), findsOneWidget);
    expect(find.textContaining('exportar seus dados ou excluir a conta em Privacidade e dados'), findsOneWidget);
    expect(find.text('Concordo em enviar esses dados ao profissional vinculado'), findsOneWidget);
    expect(find.textContaining('servidor do estudo'), findsNothing);
    await t.ensureVisible(find.text('Política de privacidade'));
    await t.tap(find.text('Política de privacidade'));
    await t.pump();
    expect(calls.where((c) => c.method == 'openUrl').map((c) => (c.arguments as Map)['url']),
        ['https://dimensono.com.br/privacidade']);
    // nothing is uploaded before the server gives the code and the consent exists
    expect(methods(), isNot(contains('setConsent')));

    await consent(t);
    expect(api.log, contains('postLink'));
    final codeCall = calls.indexWhere((c) => c.method == 'setParticipantCode');
    final consentCall = calls.indexWhere((c) => c.method == 'setConsent');
    expect((calls[codeCall].arguments as Map)['code'], '5511999998888');
    expect(consentCall, greaterThan(codeCall));
    expect(find.text('Nenhuma pulseira pareada'), findsOneWidget);
    expect(find.text('Aguardando liberação do envio'), findsNothing);
    expect(app.participantLinked, isTrue);
  });

  testWidgets('conta de demonstracao: debug_code preenche o codigo sozinho', (t) async {
    api.debugCode = '654321';
    await openMain(t);
    await toCodeStep(t);
    final field = t.widget<TextField>(find.byType(TextField).first);
    expect(field.controller!.text, '654321');
  });

  testWidgets('conta ja vinculada: mostra o profissional atual e continua sem pedir o codigo', (t) async {
    api.verifyResult = const VerifyResult('tok-2', 'linked', 'battery', '8');
    api.meResult = PatientMe('Maria', '05/03/1990', 'F', api.prof, 'battery');
    await openMain(t);
    await toCodeStep(t);
    await verifyCode(t);
    expect(find.text('Você está vinculado a Dra. Ana Souza'), findsOneWidget);
    await tapBtn(t, 'Continuar');
    expect(find.text('Antes de começar'), findsOneWidget);
    expect(api.log, isNot(contains('registration')));
  });

  testWidgets('conta vinculada: "Trocar profissional" volta ao codigo do profissional', (t) async {
    api.verifyResult = const VerifyResult('tok-2', 'linked', 'home', '8');
    api.meResult = PatientMe('Maria', '05/03/1990', 'F', api.prof, 'home');
    await openMain(t);
    await toCodeStep(t);
    await verifyCode(t);
    await t.tap(find.text('Trocar profissional'));
    await t.pumpAndSettle();
    expect(find.text('Código do profissional'), findsWidgets);
    expect(find.text('Continuar'), findsOneWidget);
  });

  testWidgets('"Seus dados": nome provisorio do servidor (wpp_/user_) nunca vem preenchido; nome real sim', (t) async {
    Future<String> nomeMostrado(String inicial) async {
      await t.pumpWidget(MaterialApp(
        home: Scaffold(body: SingleChildScrollView(child: PassoDados(key: UniqueKey(), nomeInicial: inicial, busy: false, onSubmit: (_) {}))),
      ));
      await t.pump();
      return t.widget<TextField>(find.byType(TextField).first).controller!.text;
    }

    expect(await nomeMostrado('wpp_5561999990001'), '');
    expect(await nomeMostrado('WPP_5561999990001'), '');
    expect(await nomeMostrado('user_42'), '');
    expect(await nomeMostrado('Maria Souza'), 'Maria Souza');
  });

  testWidgets('transfer_pending: avisa que a troca aguarda liberacao e segue com o vinculo atual', (t) async {
    api.referralResult = const ReferralResult('transfer_pending', 'registration');
    await openMain(t);
    await toCodeStep(t);
    await verifyCode(t);
    await typeIn(t, 0, 'DMS-ABCD1234');
    await tapBtn(t, 'Continuar');
    await tapBtn(t, 'Sim, é este');
    expect(find.textContaining('Pedido de troca enviado; aguardando liberação'), findsOneWidget);
    expect(find.text('Seus dados'), findsOneWidget);
  });

  testWidgets('telefone de profissional: pede a senha e entra pelo login', (t) async {
    api.verifyError = const ApiError(409, 'password_required');
    api.meResult = const PatientMe('', '', '', null, 'link');
    await openMain(t);
    await toCodeStep(t);
    await verifyCode(t);
    expect(find.text('Digite a sua senha'), findsOneWidget);
    await typeIn(t, 0, 'segredo');
    await tapBtn(t, 'Entrar');
    expect(api.log, contains('login:11912345678'));
    expect(api.token, 'tok-login');
    expect(find.text('Código do profissional'), findsWidgets);
  });

  testWidgets('senha de profissional errada mostra o erro', (t) async {
    api.verifyError = const ApiError(409, 'password_required');
    api.loginError = const ApiError(401, 'Credenciais inválidas');
    await openMain(t);
    await toCodeStep(t);
    await verifyCode(t);
    await typeIn(t, 0, 'errada');
    await tapBtn(t, 'Entrar');
    expect(find.text('Senha incorreta.'), findsOneWidget);
  });

  testWidgets('conta de profissional sem acesso de paciente: mensagem clara e volta ao telefone', (t) async {
    api.verifyError = const ApiError(409, 'password_required');
    api.meError = const ApiError(403, 'patient_only');
    await openMain(t);
    await toCodeStep(t);
    await verifyCode(t);
    await typeIn(t, 0, 'segredo');
    await tapBtn(t, 'Entrar');
    expect(find.text('Esta conta é de profissional; use o app com o telefone de um paciente.'), findsOneWidget);
    expect(find.text('Qual é o seu celular?'), findsOneWidget);
    expect(methods(), contains('accountClear'));
  });

  testWidgets('codigo do profissional desconhecido mostra o aviso e deixa corrigir', (t) async {
    api.previewError = const ApiError(404, 'unknown_code');
    await openMain(t);
    await toCodeStep(t);
    await verifyCode(t);
    await typeIn(t, 0, 'DMS-ZZZZ9999');
    await tapBtn(t, 'Continuar');
    expect(find.text('Não encontramos esse código. Confira com o profissional.'), findsOneWidget);
    expect(api.log, isNot(contains('referral:DMS-ZZZZ9999')));
  });

  testWidgets('codigo do profissional invalido nem chama o servidor', (t) async {
    await openMain(t);
    await toCodeStep(t);
    await verifyCode(t);
    await typeIn(t, 0, 'ABC');
    await t.pump();
    expect(find.textContaining('começa com DMS-'), findsWidgets);
    expect(t.widget<FilledButton>(find.widgetWithText(FilledButton, 'Continuar')).onPressed, isNull);
  });

  testWidgets('429 ao pedir o codigo e ao confirmar', (t) async {
    api.startError = const ApiError(429, 'Muitas tentativas');
    await openMain(t);
    await typeIn(t, 0, '11912345678');
    await tapBtn(t, 'Enviar código');
    expect(find.textContaining('códigos demais'), findsOneWidget);

    api.startError = null;
    api.verifyError = const ApiError(429, 'Muitas tentativas');
    await tapBtn(t, 'Enviar código');
    await verifyCode(t);
    expect(find.text('Muitas tentativas. Peça um novo código.'), findsOneWidget);
    expect(find.text('Pedir outro código'), findsOneWidget);
  });

  testWidgets('codigo do WhatsApp errado e sem internet', (t) async {
    api.verifyError = const ApiError(400, 'Código inválido ou expirado.');
    await openMain(t);
    await toCodeStep(t);
    await verifyCode(t);
    expect(find.textContaining('Código inválido ou expirado'), findsOneWidget);
    api.verifyError = const ApiError(0, 'network');
    await tapBtn(t, 'Confirmar');
    expect(find.textContaining('Sem conexão com a internet'), findsOneWidget);
  });

  testWidgets('cadastro: erros do servidor aparecem e a data futura nem chega no servidor', (t) async {
    await openMain(t);
    await toCodeStep(t);
    await verifyCode(t);
    await typeIn(t, 0, 'DMS-ABCD1234');
    await tapBtn(t, 'Continuar');
    await tapBtn(t, 'Sim, é este');

    await fillData(t, ano: '${DateTime.now().year + 1}');
    expect(find.textContaining('Confira a data de nascimento'), findsOneWidget);
    expect(api.log, isNot(contains('registration')));

    api.regError = const ApiError(400, '', errors: ['name: Informe o nome completo.']);
    await typeIn(t, 3, '1990');
    await tapBtn(t, 'Continuar');
    expect(find.text('Informe o nome completo.'), findsOneWidget);
    expect(find.text('Seus dados'), findsOneWidget);
  });

  testWidgets('link da actigrafia 404: nada e enviado, aparece "Aguardando liberacao" e o app tenta de novo', (t) async {
    api.postLinkError = const ApiError(404, 'Not Found');
    api.getLinkError = const ApiError(404, 'Not Found');
    await openMain(t);
    await toCodeStep(t);
    await verifyCode(t);
    await typeIn(t, 0, 'DMS-ABCD1234');
    await tapBtn(t, 'Continuar');
    await tapBtn(t, 'Sim, é este');
    await fillData(t);
    await consent(t);

    expect(methods(), isNot(contains('setParticipantCode')), reason: 'sem rota nao se inventa codigo');
    expect(app.participantLinked, isFalse);
    expect(app.consent, isTrue);
    expect(find.text('Aguardando liberação do envio'), findsWidgets);
    expect(find.text('Antes de começar'), findsNothing);

    // the route goes live: the next try gets the code
    api.getLinkError = null;
    api.postLinkError = null;
    await t.tap(find.text('Tentar agora'));
    await t.pumpAndSettle();
    expect((calls.firstWhere((c) => c.method == 'setParticipantCode').arguments as Map)['code'], '5511999998888');
    expect(find.text('Aguardando liberação do envio'), findsNothing);
  });

  testWidgets('409 code_taken explica o motivo e continua aguardando', (t) async {
    app.push({'hasSession': true, 'consentAsked': true, 'consent': true});
    api.token = 'tok';
    api.getLinkError = const ApiError(409, 'code_taken');
    await openMain(t);
    await app.refreshLink();
    await t.pump();
    expect(find.textContaining('já está vinculado a outro paciente'), findsOneWidget);
    expect(methods(), isNot(contains('setParticipantCode')));
  });

  testWidgets('ao abrir o app o codigo do servidor e relido e adotado quando mudou', (t) async {
    app.push({'hasSession': true, 'consentAsked': true, 'consent': true, 'participantLinked': true, 'participant': '5511900000000'});
    api.token = 'tok';
    api.existingCode = '5511988887777';
    await openMain(t);
    await app.refreshLink();
    await t.pump();
    expect(api.log, ['getLink']);
    expect((calls.firstWhere((c) => c.method == 'setParticipantCode').arguments as Map)['code'], '5511988887777');
    expect(app.participant, '5511988887777');

    // same code: nothing to do
    calls.clear();
    await app.refreshLink();
    expect(methods(), isNot(contains('setParticipantCode')));
  });

  testWidgets('401: aparece "Entrar novamente" e o codigo atual continua valendo', (t) async {
    app.push({'hasSession': true, 'consentAsked': true, 'consent': true, 'participantLinked': true, 'participant': '5511900000000'});
    api.token = 'tok';
    api.getLinkError = const ApiError(401, 'expired');
    await openMain(t);
    await app.refreshLink();
    await t.pump();
    expect(find.text('Sua sessão expirou'), findsOneWidget);
    expect(find.text('Entrar novamente'), findsOneWidget);
    expect(methods(), contains('accountExpired'));
    expect(app.participantLinked, isTrue);
    expect(app.participant, '5511900000000');
    expect(find.text('Nenhuma pulseira pareada'), findsOneWidget);
  });

  testWidgets('"Entrar novamente" abre o fluxo no telefone', (t) async {
    app.push({'hasSession': true, 'sessionExpired': true, 'consentAsked': true, 'consent': true, 'participantLinked': true, 'participant': '5511900000000'});
    await openMain(t);
    await t.tap(find.text('Entrar novamente'));
    await t.pumpAndSettle();
    expect(find.text('Qual é o seu celular?'), findsOneWidget);
  });

  testWidgets('instalacao antiga (vinculada, sem conta) segue direto para a tela inicial', (t) async {
    app.push({'participantLinked': true, 'participant': 'ABCD', 'consent': true, 'consentAsked': true});
    await openMain(t);
    expect(find.text('Qual é o seu celular?'), findsNothing);
    expect(find.text('Nenhuma pulseira pareada'), findsOneWidget);
  });

  testWidgets('instalacao nova com link /p/: o codigo do profissional vem preenchido e nunca vira participante', (t) async {
    app.push({'professionalPrefill': 'DMS-ABCD1234'});
    await openMain(t);
    await toCodeStep(t);
    await verifyCode(t);
    final field = t.widget<TextField>(find.byType(TextField).first);
    expect(field.controller!.text, 'DMS-ABCD1234');
    await tapBtn(t, 'Continuar');
    expect(methods(), contains('clearProfessionalPrefill'));
    expect(methods(), isNot(contains('setParticipantCode')));
  });

  testWidgets('excluir conta: confirma, chama o servidor, apaga o celular e volta ao fluxo de entrada', (t) async {
    app.push({'hasSession': true, 'consentAsked': true, 'consent': true, 'participantLinked': true, 'participant': '5511900000000'});
    native = {'hasSession': true, 'participantLinked': true};
    t.view.physicalSize = const Size(800, 2400);
    t.view.devicePixelRatio = 1.0;
    addTearDown(t.view.reset);
    await t.pumpWidget(MaterialApp(home: PrivacyScreen(app: app)));
    await t.pump();
    expect(find.text('Política de privacidade'), findsOneWidget);
    expect(find.text('Excluir conta pelo site'), findsOneWidget);

    await t.tap(find.text('Excluir minha conta'));
    await t.pumpAndSettle();
    expect(find.text('Excluir minha conta?'), findsOneWidget);
    expect(api.log, isNot(contains('deleteMe')));
    await t.tap(find.widgetWithText(FilledButton, 'Excluir'));
    await t.pumpAndSettle();

    expect(api.log, contains('deleteMe'));
    expect(methods(), contains('wipeAll'));
    expect(api.token, isNull);
    expect(app.hasSession, isFalse);
    expect(app.needsEntry, isTrue);
  });

  testWidgets('excluir conta: falha do servidor nao apaga nada no celular', (t) async {
    app.push({'hasSession': true, 'consentAsked': true, 'consent': true, 'participantLinked': true, 'participant': '5511900000000'});
    api.deleteError = const ApiError(500, 'boom');
    t.view.physicalSize = const Size(800, 2400);
    t.view.devicePixelRatio = 1.0;
    addTearDown(t.view.reset);
    await t.pumpWidget(MaterialApp(home: PrivacyScreen(app: app)));
    await t.pump();
    await t.tap(find.text('Excluir minha conta'));
    await t.pumpAndSettle();
    await t.tap(find.widgetWithText(FilledButton, 'Excluir'));
    await t.pumpAndSettle();
    expect(methods(), isNot(contains('wipeAll')));
    expect(find.textContaining('Não foi possível excluir agora'), findsOneWidget);
    expect(app.hasSession, isTrue);
  });

  testWidgets('codigo removido no servidor (null): desvincula no celular e fica aguardando, sem enviar com o codigo antigo', (t) async {
    app.push({'hasSession': true, 'consentAsked': true, 'consent': true, 'participantLinked': true, 'participant': '5511900000000'});
    api.token = 'tok';
    api.existingCode = null;
    api.postLinkError = const ApiError(409, 'not_linked');
    await openMain(t);
    await app.refreshLink();
    await t.pump();
    expect(methods(), contains('unlinkParticipant'));
    expect(methods(), isNot(contains('setParticipantCode')));
    expect(api.log, ['getLink', 'postLink']);
    expect(app.participantLinked, isFalse);
    expect(app.awaitingRelease, isTrue);
    expect(find.text('Aguardando liberação do envio'), findsWidgets);
    expect(find.text('Falta vincular o profissional.'), findsOneWidget);
  });

  testWidgets('codigo removido no servidor mas o paciente segue vinculado: o POST devolve o codigo e volta a enviar', (t) async {
    app.push({'hasSession': true, 'consentAsked': true, 'consent': true, 'participantLinked': true, 'participant': '5511900000000'});
    api.token = 'tok';
    api.existingCode = null;
    await openMain(t);
    await app.refreshLink();
    await t.pump();
    expect(methods(), containsAllInOrder(['unlinkParticipant', 'setParticipantCode']));
    expect(app.participantLinked, isTrue);
    expect(app.participant, '5511999998888');
  });

  testWidgets('410 participant_deleted (via status): a sessao cai, o fluxo de entrada volta e os dados ficam no celular', (t) async {
    native = {'hasSession': true, 'consentAsked': true, 'consent': false, 'participantLinked': false, 'participantDeleted': true};
    api.token = 'tok';
    await app.reloadStatus();
    await openMain(t);
    expect(methods(), contains('accountClear'));
    expect(methods(), isNot(contains('wipeAll')));
    expect(app.hasSession, isFalse);
    expect(app.needsEntry, isTrue);
    expect(find.text('Qual é o seu celular?'), findsOneWidget);
    expect(find.textContaining('Esta conta foi excluída'), findsOneWidget);
    expect(find.text('Aguardando liberação do envio'), findsNothing);
  });

  test('excluir conta: o envio e desligado ANTES de chamar o servidor', () async {
    app.push({'hasSession': true, 'consentAsked': true, 'consent': true, 'participantLinked': true, 'participant': '5511900000000'});
    native = {'consent': true, 'consentAsked': true, 'participantLinked': true};
    bool? consentWhenDeleting;
    api.onDelete = () => consentWhenDeleting = native['consent'] as bool?;
    expect(await app.deleteAccount(), 'ok');
    expect(consentWhenDeleting, isFalse);
    expect(methods(), containsAllInOrder(['setConsent', 'wipeAll']));
    expect((calls.firstWhere((c) => c.method == 'setConsent').arguments as Map)['value'], isFalse);
  });

  test('excluir conta: se o servidor recusar, o envio volta como estava', () async {
    app.push({'hasSession': true, 'consentAsked': true, 'consent': true, 'participantLinked': true, 'participant': '5511900000000'});
    native = {'consent': true, 'consentAsked': true, 'participantLinked': true};
    api.deleteError = const ApiError(500, 'boom');
    expect(await app.deleteAccount(), 'error');
    expect(native['consent'], isTrue);
    expect(app.consent, isTrue);
    expect(methods(), isNot(contains('wipeAll')));
  });

  test('excluir conta: falha ao apagar o celular devolve error, nunca ok', () async {
    app.push({'hasSession': true, 'consentAsked': true, 'consent': true, 'participantLinked': true, 'participant': '5511900000000'});
    native = {'consent': true, 'consentAsked': true, 'participantLinked': true};
    wipeFails = true;
    expect(await app.deleteAccount(), 'error');
    expect(api.log, contains('deleteMe'));
    expect(app.hasSession, isTrue, reason: 'o celular ainda guarda a conta: nao finge que limpou');
  });

  testWidgets('entrar novamente com OUTRO telefone: recusa, pede o final do numero e descarta a sessao nova', (t) async {
    app.push({'hasSession': true, 'sessionExpired': true, 'consentAsked': true, 'consent': true, 'participantLinked': true,
      'participant': '5511900000000', 'accountPhone': '5678'});
    app.storedUserId = '7';
    api.verifyResult = const VerifyResult('tok-outro', 'existing', 'home', '99');
    await openMain(t);
    await t.tap(find.text('Entrar novamente'));
    await t.pumpAndSettle();
    await toCodeStep(t);
    await verifyCode(t);
    expect(find.text('Entre com o número de final 5678.'), findsOneWidget);
    expect(find.text('Qual é o seu celular?'), findsOneWidget);
    expect(methods(), isNot(contains('accountSave')), reason: 'a sessao nova nunca e gravada');
    expect(api.token, isNot('tok-outro'));
    expect(app.participant, '5511900000000');
  });

  testWidgets('entrar novamente com o MESMO usuario grava a sessao', (t) async {
    app.push({'hasSession': true, 'sessionExpired': true, 'consentAsked': true, 'consent': true, 'participantLinked': true,
      'participant': '5511900000000', 'accountPhone': '5678'});
    app.storedUserId = '7';
    api.verifyResult = const VerifyResult('tok-novo', 'existing', 'link', '7');
    await openMain(t);
    await t.tap(find.text('Entrar novamente'));
    await t.pumpAndSettle();
    await toCodeStep(t);
    await verifyCode(t);
    expect(methods(), contains('accountSave'));
    expect(find.textContaining('Entre com o número de final'), findsNothing);
  });

  testWidgets('privacidade: "Codigo do participante" mostra so os 4 ultimos digitos, ou aguardando liberacao', (t) async {
    t.view.physicalSize = const Size(800, 2400);
    t.view.devicePixelRatio = 1.0;
    addTearDown(t.view.reset);
    app.push({'hasSession': true, 'consentAsked': true, 'consent': true, 'participantLinked': true, 'participant': '5511999998888'});
    await t.pumpWidget(MaterialApp(home: PrivacyScreen(app: app)));
    await t.pump();
    expect(find.text('Código do participante'), findsOneWidget);
    expect(find.text('Código do profissional'), findsNothing);
    expect(find.text('…8888'), findsOneWidget);
    expect(find.textContaining('5511999998888'), findsNothing);

    app.push({'participantLinked': false});
    await t.pump();
    expect(find.text('aguardando liberação'), findsOneWidget);
    expect(find.text('…8888'), findsNothing);
  });

  testWidgets('links da politica e da exclusao abrem pelo nativo', (t) async {
    t.view.physicalSize = const Size(800, 2400);
    t.view.devicePixelRatio = 1.0;
    addTearDown(t.view.reset);
    await t.pumpWidget(MaterialApp(home: PrivacyScreen(app: app)));
    await t.pump();
    expect(find.text('Excluir minha conta'), findsNothing, reason: 'sem conta so ha os links do site');
    await t.tap(find.text('Política de privacidade'));
    await t.pump();
    await t.tap(find.text('Excluir conta pelo site'));
    await t.pump();
    final urls = calls.where((c) => c.method == 'openUrl').map((c) => (c.arguments as Map)['url']).toList();
    expect(urls, ['https://dimensono.com.br/privacidade', 'https://dimensono.com.br/exclusao']);
  });

  testWidgets('Privacidade e dados: mantem o aviso AGPL sem link do codigo-fonte (pedido do Bruno)', (t) async {
    t.view.physicalSize = const Size(800, 2400);
    t.view.devicePixelRatio = 1.0;
    addTearDown(t.view.reset);
    await t.pumpWidget(MaterialApp(home: PrivacyScreen(app: app)));
    await t.pump();
    expect(find.textContaining('o código também é AGPL-3.0'), findsOneWidget);
    expect(find.textContaining('github.com'), findsNothing);
  });

  test('nascimento: valida dia, mes, ano de 4 digitos, 1900 e data futura', () {
    final hoje = DateTime(2026, 11, 5);
    expect(nascimentoIso('5', '3', '1990', hoje: hoje), '1990-03-05');
    expect(nascimentoIso('31', '02', '1990', hoje: hoje), isNull);
    expect(nascimentoIso('29', '02', '2000', hoje: hoje), '2000-02-29');
    expect(nascimentoIso('01', '01', '1899', hoje: hoje), isNull);
    expect(nascimentoIso('01', '01', '90', hoje: hoje), isNull);
    expect(nascimentoIso('06', '11', '2026', hoje: hoje), isNull);
    expect(nascimentoIso('05', '11', '2026', hoje: hoje), '2026-11-05');
    expect(nascimentoIso('', '01', '1990', hoje: hoje), isNull);
  });

  test('o codigo do profissional e normalizado (aparado e em maiusculas)', () {
    expect(normalizaProfissional(' dms-abcd1234 '), 'DMS-ABCD1234');
    expect(profissionalValido('dms-abcd1234'), isTrue);
    expect(profissionalValido('DMS-ABC'), isFalse);
    expect(profissionalValido('ABCD1234'), isFalse);
    expect(profissionalValido('DMS-ABCD1234567890123'), isFalse);
  });

  test('textos da entrada nao citam Supabase nem Huawei', () {
    final dirs = [Directory('lib/screens/entrada')];
    for (final d in dirs) {
      for (final f in d.listSync(recursive: true).whereType<File>().where((f) => f.path.endsWith('.dart'))) {
        final text = f.readAsStringSync().toLowerCase();
        expect(text.contains('supabase'), isFalse, reason: f.path);
        expect(text.contains('huawei'), isFalse, reason: f.path);
      }
    }
    expect(File('lib/dimensono_api.dart').readAsStringSync().toLowerCase().contains('supabase'), isFalse);
  });

  group('HttpDimensonApi', () {
    late HttpServer server;
    late List<String> seen;
    late int status;
    late String body;

    setUp(() async {
      HttpOverrides.global = null; // the test binding blocks real sockets by default
      seen = [];
      status = 200;
      body = '{}';
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      server.listen((req) async {
        final text = await utf8.decoder.bind(req).join();
        seen.add('${req.method} ${req.uri.path} auth=${req.headers.value('authorization')} body=$text');
        req.response.statusCode = status;
        req.response.headers.contentType = ContentType.json;
        req.response.write(body);
        await req.response.close();
      });
    });

    tearDown(() => server.close(force: true));

    HttpDimensonApi client() => HttpDimensonApi(baseUrl: 'http://127.0.0.1:${server.port}/api');

    test('start e verify leem o corpo e nao mandam token', () async {
      body = '{"challenge_id":"abc12345","expires_at":"x","debug_code":"111222"}';
      final r = await client().start('11912345678');
      expect(r.challengeId, 'abc12345');
      expect(r.debugCode, '111222');
      expect(seen.single, 'POST /api/auth/app/start auth=null body={"phone":"11912345678"}');
      body = '{"access_token":"T","token_type":"bearer","account_state":"linked","next":"home","user_id":9}';
      final v = await client().verify('abc12345', '123456');
      expect((v.token, v.accountState, v.next, v.userId), ('T', 'linked', 'home', '9'));
    });

    test('rotas do paciente mandam o Bearer e o link le o codigo', () async {
      final c = client()..token = 'T';
      body = '{"participant_code":"5511999998888","linked_by":"patient"}';
      expect((await c.getLink()).participantCode, '5511999998888');
      body = '{"participant_code":null,"linked_by":null}';
      expect((await c.getLink()).participantCode, isNull);
      expect(seen.first, startsWith('GET /api/patient/actigraphy/link auth=Bearer T'));
      body = '{}';
      await c.deleteMe();
      expect(seen.last, 'DELETE /api/patient/me auth=Bearer T body={"confirmation":"EXCLUIR"}');
    });

    test('erros viram ApiError tipado', () async {
      status = 409;
      body = '{"detail":"password_required"}';
      await expectLater(client().verify('abc12345', '123456'), throwsA(isA<ApiError>().having((e) => e.code, 'code', 'password_required')));
      status = 400;
      body = '{"detail":{"errors":["name: curto"]}}';
      await expectLater(client().registration('A', '1990-01-01', 'F'), throwsA(isA<ApiError>().having((e) => e.errors, 'errors', ['name: curto'])));
      status = 429;
      body = '{"detail":"Muitas tentativas."}';
      await expectLater(client().start('11912345678'), throwsA(isA<ApiError>().having((e) => e.tooMany, 'tooMany', true)));
    });

    test('sem servidor vira erro de rede', () async {
      final c = HttpDimensonApi(baseUrl: 'http://127.0.0.1:1/api', timeout: const Duration(seconds: 3));
      await expectLater(c.start('11912345678'), throwsA(isA<ApiError>().having((e) => e.isNetwork, 'isNetwork', true)));
    });
  });
}

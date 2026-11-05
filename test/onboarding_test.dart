import 'dart:io';

import 'package:bandsono/app_state.dart';
import 'package:bandsono/screens/main_screen.dart';
import 'package:bandsono/screens/privacy_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

class FakeAppState extends AppState {
  void push(Map<String, dynamic> s) {
    status = {...status, ...s};
    notifyListeners();
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late FakeAppState app;
  late List<MethodCall> calls;
  late bool codeAccepted;

  setUp(() {
    calls = [];
    codeAccepted = true;
    app = FakeAppState();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('bandsono/band'), (call) async {
      calls.add(call);
      if (call.method == 'setParticipantCode') return codeAccepted;
      if (call.method == 'status') return <String, dynamic>{...app.status, 'participant': 'NOVO-2', 'pendingParticipant': ''};
      return true;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('bandsono/band'), null);
    app.dispose();
  });

  Future<void> openMain(WidgetTester t) async {
    await t.pumpWidget(MaterialApp(home: Scaffold(body: SafeArea(child: MainScreen(app: app)))));
  }

  Finder continueButton() => find.widgetWithText(FilledButton, 'Continuar');
  bool enabled(WidgetTester t) => t.widget<FilledButton>(continueButton()).onPressed != null;

  testWidgets('(a) instalacao nova (sem vinculo) mostra o fluxo de entrada, nao o cartao do codigo livre', (t) async {
    await openMain(t);
    expect(find.text('Qual é o seu celular?'), findsOneWidget);
    expect(find.text('Antes de começar'), findsNothing);
    expect(find.text('Cole aqui o código que você recebeu'), findsNothing);
    expect(find.text('Parear pulseira'), findsNothing);
  });

  testWidgets('(b) instalacao antiga vinculada por link, sem consentimento: cartao so de consentimento', (t) async {
    app.push({'participantLinked': true, 'participant': 'ABC-123'});
    await openMain(t);
    expect(find.text('Antes de começar'), findsOneWidget);
    expect(find.text('Vinculado ao código ABC-123'), findsOneWidget);
    expect(find.text('Concordo em enviar esses dados ao profissional vinculado'), findsOneWidget);
    expect(find.text('Política de privacidade'), findsOneWidget);
    expect(find.textContaining('servidor do estudo'), findsNothing);
    expect(find.byType(TextField), findsNothing);
    expect(enabled(t), isFalse, reason: 'falta o consentimento');
    await t.ensureVisible(find.byType(Checkbox));
    await t.tap(find.byType(Checkbox));
    await t.pump();
    expect(enabled(t), isTrue);
    await t.ensureVisible(continueButton());
    await t.tap(continueButton());
    await t.pumpAndSettle();
    expect(calls.map((c) => c.method), ['setConsent']);
    expect((calls[0].arguments as Map)['value'], true);
    expect(find.text('Antes de começar'), findsNothing);
    expect(find.text('Nenhuma pulseira pareada'), findsOneWidget);
  });

  testWidgets('(c2) nativo recusa o consentimento: mostra mensagem amigavel', (t) async {
    codeAccepted = false;
    app.push({'participantLinked': true, 'participant': 'ABCD'});
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('bandsono/band'), (call) async {
      calls.add(call);
      return false;
    });
    await openMain(t);
    await t.ensureVisible(find.byType(Checkbox));
    await t.tap(find.byType(Checkbox));
    await t.pump();
    await t.ensureVisible(continueButton());
    await t.tap(continueButton());
    await t.pumpAndSettle();
    expect(find.textContaining('Não consegui salvar'), findsOneWidget);
    expect(calls.map((c) => c.method), ['setConsent']);
  });

  testWidgets('(d) ja vinculado por link mostra "Vinculado ao codigo X" sem campo', (t) async {
    app.push({'participant': 'ESTUDO-7', 'participantLinked': true});
    await openMain(t);
    expect(find.text('Vinculado ao código ESTUDO-7'), findsOneWidget);
    expect(find.byType(TextField), findsNothing);
    await t.ensureVisible(find.byType(Checkbox));
    await t.tap(find.byType(Checkbox));
    await t.pump();
    await t.ensureVisible(continueButton());
    await t.tap(continueButton());
    await t.pumpAndSettle();
    expect(calls.map((c) => c.method), ['setConsent']);
  });

  testWidgets('(e) calibrating=true mostra a linha de calibracao', (t) async {
    app.push({'participantLinked': true, 'participant': 'ABCD', 'consent': true, 'consentAsked': true, 'calibrating': false});
    await openMain(t);
    const line = 'Calibrando a pulseira: fique parado por cerca de 1 minuto.';
    expect(find.text(line), findsNothing);
    app.push({'calibrating': true});
    await t.pump();
    expect(find.text(line), findsOneWidget);
  });

  testWidgets('(f) tela Privacidade nao tem URL/chave/servidor e tem consentimento e exportar', (t) async {
    app.push({'participantLinked': true, 'participant': 'ABCD', 'consent': true, 'address': 'AA:BB', 'state': 'READY'});
    t.view.physicalSize = const Size(800, 2400);
    t.view.devicePixelRatio = 1.0;
    addTearDown(t.view.reset);
    await t.pumpWidget(MaterialApp(home: PrivacyScreen(app: app)));
    await t.pump();
    expect(find.text('Privacidade e dados'), findsOneWidget);
    expect(find.text('Enviar meus dados ao profissional'), findsOneWidget);
    expect(find.text('Exportar meus dados (CSV)'), findsOneWidget);
    expect(find.text('Código do participante'), findsOneWidget);
    expect(find.text('ABCD'), findsOneWidget);
    expect(find.text('Otimização de bateria'), findsOneWidget);
    expect(find.textContaining('AGPL'), findsOneWidget);
    for (final w in ['URL', 'Endereço do servidor', 'Chave', 'Testar conexão', 'Enviar agora', 'Calibr', 'Notificações']) {
      expect(find.textContaining(w), findsNothing, reason: 'apareceu "$w"');
    }
    // desligar o consentimento pede confirmacao
    await t.tap(find.byType(Switch));
    await t.pumpAndSettle();
    expect(find.text('Parar de enviar os dados?'), findsOneWidget);
    expect(calls.where((c) => c.method == 'setConsent'), isEmpty);
    await t.tap(find.text('Confirmar'));
    await t.pumpAndSettle();
    expect(calls.where((c) => c.method == 'setConsent').single.arguments, {'value': false});
  });

  test('(g) nenhum texto "Supabase" em lib/', () {
    final hits = <String>[];
    for (final f in Directory('lib').listSync(recursive: true).whereType<File>()) {
      if (f.path.endsWith('.dart') && f.readAsStringSync().toLowerCase().contains('supabase')) hits.add(f.path);
    }
    expect(hits, isEmpty);
  });

  const linked = {'participantLinked': true, 'participant': 'ABCD', 'consentAsked': true};

  testWidgets('(1) consentAsked: desligar o envio nao volta ao cartao', (t) async {
    app.push({...linked, 'consent': false, 'address': 'AA:BB', 'state': 'READY'});
    await openMain(t);
    expect(find.text('Antes de começar'), findsNothing);
    expect(find.text('desligado · veja Privacidade e dados'), findsOneWidget);
  });

  testWidgets('(1b) vinculado mas consentAsked=false mostra o cartao', (t) async {
    app.push({'participantLinked': true, 'participant': 'ABCD', 'consent': true, 'consentAsked': false});
    await openMain(t);
    expect(find.text('Antes de começar'), findsOneWidget);
  });

  testWidgets('(2) codigo pendente: dialogo; "Manter" chama rejectParticipantChange', (t) async {
    app.push({...linked, 'consent': true, 'pendingParticipant': 'NOVO-2'});
    await openMain(t);
    await t.pumpAndSettle();
    expect(find.text('Trocar o código do participante?'), findsOneWidget);
    expect(find.text('Este celular está vinculado ao código ABCD. O link recebido é do código NOVO-2.'), findsOneWidget);
    await t.tap(find.text('Manter ABCD'));
    await t.pumpAndSettle();
    expect(calls.map((c) => c.method), ['rejectParticipantChange']);
    expect(find.text('Trocar o código do participante?'), findsNothing);
  });

  testWidgets('(2b) "Trocar para" chama confirmParticipantChange', (t) async {
    app.push({...linked, 'consent': true});
    await openMain(t);
    app.push({'pendingParticipant': 'NOVO-2'});
    await t.pumpAndSettle();
    await t.tap(find.text('Trocar para NOVO-2'));
    await t.pumpAndSettle();
    expect(calls.map((c) => c.method), ['confirmParticipantChange', 'status']);
    expect(find.text('Trocar o código do participante?'), findsNothing);
    expect(app.participant, 'NOVO-2');
  });

  testWidgets('(3) linkError aparece no cartao de consentimento da instalacao antiga', (t) async {
    app.push({'participantLinked': true, 'participant': 'ABCD', 'linkError': 'Este link não é válido.'});
    await openMain(t);
    expect(find.text('Antes de começar'), findsOneWidget);
    expect(find.text('Este link não é válido.'), findsOneWidget);
  });

  testWidgets('(3b) linkError ja vinculado aparece em SnackBar', (t) async {
    app.push({...linked, 'consent': true});
    await openMain(t);
    app.push({'linkError': 'Este link não é válido.'});
    await t.pump();
    await t.pump(const Duration(milliseconds: 300));
    expect(find.text('Este link não é válido.'), findsOneWidget);
    expect(find.byType(SnackBar), findsOneWidget);
  });
}

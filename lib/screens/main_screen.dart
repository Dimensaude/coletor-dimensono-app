import 'package:flutter/material.dart';

import '../app_state.dart';
import '../band_api.dart';
import '../theme.dart';
import '../widgets.dart';
import 'entrada/entrada_flow.dart';
import 'onboarding_card.dart';
import 'pair_screen.dart';
import 'privacy_screen.dart';

class MainScreen extends StatefulWidget {
  final AppState app;

  const MainScreen({super.key, required this.app});

  @override
  State<MainScreen> createState() => _MainScreenState();
}

class _MainScreenState extends State<MainScreen> {
  bool _dialogOpen = false;
  String _shownError = '';

  AppState get app => widget.app;

  @override
  void initState() {
    super.initState();
    app.addListener(_react);
    WidgetsBinding.instance.addPostFrameCallback((_) => _react());
  }

  @override
  void dispose() {
    app.removeListener(_react);
    super.dispose();
  }

  /// Reacts to link events: a different code waiting for confirmation, or a link error.
  void _react() {
    if (!mounted) return;
    final err = app.linkError;
    if (err.isEmpty) {
      _shownError = '';
    } else if (err != _shownError) {
      _shownError = err;
      // While the entry flow or the consent card is up the error is shown inside it.
      if (!app.needsEntry && !app.needsLegacyConsent) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(err)));
      }
    }
    if (app.pendingParticipant.isNotEmpty && !_dialogOpen) _askChange();
  }

  Future<void> _askChange() async {
    _dialogOpen = true;
    final current = app.participant;
    final pending = app.pendingParticipant;
    final change = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (c) => AlertDialog(
        title: const Text('Trocar o código do participante?'),
        content: Text('Este celular está vinculado ao código $current. O link recebido é do código $pending.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: Text('Manter $current')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: Text('Trocar para $pending')),
        ],
      ),
    );
    try {
      if (change == true) {
        await app.confirmParticipantChange();
      } else {
        await app.rejectParticipantChange();
      }
    } catch (e) {
      debugPrint('participant change: $e');
    }
    _dialogOpen = false;
    if (mounted && app.pendingParticipant.isNotEmpty && app.pendingParticipant != pending) _askChange();
  }

  void _openPrivacy(BuildContext context) {
    Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => PrivacyScreen(app: app)));
  }

  void _openPair(BuildContext context) {
    Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => PairScreen(app: app)));
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: app,
      builder: (context, _) => Padding(
        padding: const EdgeInsets.fromLTRB(22, 8, 22, 26),
        child: Column(
          children: [
            _appbar(context),
            if (app.calibrating) _calibratingLine(),
            if (!app.needsEntry && !app.needsLegacyConsent) ..._accountBanners(context),
            Expanded(
              child: app.needsEntry
                  ? SingleChildScrollView(padding: const EdgeInsets.only(top: 8), child: EntradaFlow(app: app))
                  : app.needsLegacyConsent
                      ? SingleChildScrollView(padding: const EdgeInsets.only(top: 8), child: OnboardingCard(app: app))
                      : (app.paired ? _connected(context) : _notPaired(context)),
            ),
          ],
        ),
      ),
    );
  }

  List<Widget> _accountBanners(BuildContext context) {
    return [
      if (app.awaitingRelease)
        _banner(
          icon: Icons.hourglass_empty,
          title: 'Aguardando liberação do envio',
          text: app.linkProblem.isNotEmpty
              ? app.linkProblem
              : 'Os dados ficam guardados no celular e serão enviados quando o envio for liberado.',
          action: 'Tentar agora',
          onAction: app.refreshLink,
        ),
      if (app.sessionExpired)
        _banner(
          icon: Icons.lock_outline,
          title: 'Sua sessão expirou',
          text: 'A coleta e o envio continuam. Entre de novo para atualizar sua conta.',
          action: 'Entrar novamente',
          onAction: () => Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => EntradaTela(app: app))),
        ),
    ];
  }

  Widget _banner({required IconData icon, required String title, required String text, required String action, required VoidCallback onAction}) {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.fromLTRB(12, 10, 6, 4),
      decoration: BoxDecoration(color: AppColors.dawnBg, borderRadius: BorderRadius.circular(12), border: Border.all(color: const Color(0xFFF0DBB0))),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Padding(padding: const EdgeInsets.only(top: 1), child: Icon(icon, color: AppColors.dawn, size: 18)),
          const SizedBox(width: 10),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w700, color: Color(0xFF8A5A00))),
              const SizedBox(height: 2),
              Text(text, style: const TextStyle(fontSize: 12.5, color: Color(0xFF8A5A00), height: 1.35)),
            ]),
          ),
        ]),
        Align(alignment: Alignment.centerRight, child: TextButton(onPressed: onAction, child: Text(action))),
      ]),
    );
  }

  Widget _appbar(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        children: [
          const BrandMark(),
          const SizedBox(width: 11),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: const [
              Text('Dimensono', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w700, color: AppColors.ink, height: 1.0)),
              SizedBox(height: 2),
              Text('COLETOR',
                  style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, letterSpacing: 2.0, color: AppColors.muted)),
            ],
          ),
          const Spacer(),
          Material(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(13),
            child: InkWell(
              borderRadius: BorderRadius.circular(13),
              onTap: () => _openPrivacy(context),
              child: Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(13),
                  border: Border.all(color: AppColors.line),
                ),
                child: const Icon(Icons.settings_outlined, color: AppColors.muted, size: 21),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _calibratingLine() {
    return const Padding(
      padding: EdgeInsets.only(top: 4, bottom: 2),
      child: Row(children: [
        Icon(Icons.hourglass_empty, size: 15, color: AppColors.faint),
        SizedBox(width: 8),
        Expanded(
          child: Text('Calibrando a pulseira: fique parado por cerca de 1 minuto.',
              style: TextStyle(fontSize: 12.5, color: AppColors.muted, height: 1.4)),
        ),
      ]),
    );
  }

  // --------------------------------------------------------------- not paired
  Widget _notPaired(BuildContext context) {
    // Scrolls when a banner above takes room on a small screen
    return LayoutBuilder(
      builder: (context, box) => SingleChildScrollView(
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: box.maxHeight),
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const BrandMark(size: 76),
                const SizedBox(height: 22),
                const Text('Nenhuma pulseira pareada', style: AppText.h1, textAlign: TextAlign.center),
                const SizedBox(height: 10),
                const Padding(
                  padding: EdgeInsets.symmetric(horizontal: 12),
                  child: Text(
                    'Pareie a pulseira uma vez. Depois o app conecta sozinho e envia os dados ao servidor.',
                    textAlign: TextAlign.center,
                    style: TextStyle(fontSize: 14.5, color: AppColors.muted, height: 1.5),
                  ),
                ),
                const SizedBox(height: 28),
                SizedBox(
                  width: 240,
                  child: PrimaryButton(label: 'Parear pulseira', icon: Icons.bluetooth_searching, onPressed: () => _openPair(context)),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  // --------------------------------------------------------------- connected
  Widget _connected(BuildContext context) {
    final s = _hero(app);
    return Column(
      children: [
        if (app.linkStuck) ...[
          const SizedBox(height: 4),
          _stuckBanner(),
        ],
        Expanded(
          child: LayoutBuilder(
            builder: (context, box) => SingleChildScrollView(
              child: ConstrainedBox(
                constraints: BoxConstraints(minHeight: box.maxHeight),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    _ring(s),
                    const SizedBox(height: 24),
                    Text(s.title, style: const TextStyle(fontFamily: AppText.serif, fontSize: 23, fontWeight: FontWeight.w700, color: AppColors.ink)),
                    const SizedBox(height: 8),
                    Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                      Container(width: 8, height: 8, decoration: BoxDecoration(color: s.dot, shape: BoxShape.circle)),
                      const SizedBox(width: 7),
                      Flexible(child: Text(s.sub, style: AppText.sub.copyWith(fontSize: 14), textAlign: TextAlign.center)),
                    ]),
                    const SizedBox(height: 28),
                    _infoRows(context),
                  ],
                ),
              ),
            ),
          ),
        ),
        _button(context, s),
        const SizedBox(height: 14),
        Text(
          app.consent
              ? 'O app sincroniza sozinho e envia os dados ao profissional vinculado em segundo plano.'
              : 'O envio ao profissional vinculado está desligado em Privacidade e dados.',
          textAlign: TextAlign.center,
          style: const TextStyle(fontSize: 12.5, color: AppColors.faint, height: 1.5),
        ),
      ],
    );
  }

  Widget _ring(_Hero s) {
    return SizedBox(
      width: 154,
      height: 154,
      child: Stack(
        alignment: Alignment.center,
        children: [
          SizedBox(
            width: 154,
            height: 154,
            child: CircularProgressIndicator(
              value: s.ringValue,
              strokeWidth: 5,
              color: s.ringColor,
              backgroundColor: AppColors.line,
              strokeCap: StrokeCap.round,
            ),
          ),
          Container(
            width: 112,
            height: 112,
            decoration: BoxDecoration(
              color: AppColors.surface,
              shape: BoxShape.circle,
              border: Border.all(color: AppColors.line),
              boxShadow: const [BoxShadow(color: Color(0x332E4FA3), blurRadius: 22, offset: Offset(0, 10))],
            ),
            child: s.syncing
                ? const Padding(
                    padding: EdgeInsets.all(34),
                    child: CircularProgressIndicator(strokeWidth: 4, color: AppColors.brand))
                : Icon(s.icon, size: 46, color: s.ringColor),
          ),
        ],
      ),
    );
  }

  Widget _infoRows(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(border: Border(top: BorderSide(color: AppColors.line))),
      child: Column(
        children: [
          _row(
            icon: Icons.sync,
            title: 'Última sincronização',
            sub: app.lastSync > 0 ? relativeTime(app.lastSync) : 'ainda não sincronizou',
            chip: app.lastSync > 0
                ? StatusChip(app.lastSyncOk ? 'em dia' : 'falhou',
                    fg: app.lastSyncOk ? AppColors.ok : AppColors.danger,
                    bg: app.lastSyncOk ? AppColors.okBg : const Color(0xFFFBEAEC))
                : null,
          ),
          _row(
            icon: Icons.cloud_upload_outlined,
            title: 'Envio ao servidor',
            sub: _uploadSub(),
            chip: _uploadChip(),
          ),
        ],
      ),
    );
  }

  String _uploadSub() {
    if (!app.consent) return 'desligado · veja Privacidade e dados';
    if (app.awaitingRelease) return 'aguardando liberação do envio';
    if (app.sbUploading) return 'enviando ao profissional vinculado…';
    if (app.sbPending > 0) return '${app.sbPending} registros pendentes';
    return 'profissional vinculado · nada pendente';
  }

  Widget? _uploadChip() {
    if (!app.consent) return const StatusChip('desligado', fg: AppColors.muted, bg: AppColors.tint);
    if (app.awaitingRelease) return const StatusChip('aguardando', fg: AppColors.dawn, bg: AppColors.dawnBg);
    if (app.sbUploading) return const StatusChip('enviando', fg: AppColors.brand, bg: AppColors.tint);
    if (!app.sbLastUploadOk) return const StatusChip('erro', fg: AppColors.danger, bg: Color(0xFFFBEAEC));
    if (app.sbPending > 0) return StatusChip('pendente', fg: AppColors.dawn, bg: AppColors.dawnBg);
    return const StatusChip('em dia');
  }

  Widget _row({required IconData icon, required String title, required String sub, Widget? chip}) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 15, horizontal: 2),
      decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: AppColors.line))),
      child: Row(
        children: [
          Container(
            width: 38,
            height: 38,
            decoration: BoxDecoration(color: AppColors.tint, borderRadius: BorderRadius.circular(11)),
            child: Icon(icon, size: 19, color: AppColors.muted),
          ),
          const SizedBox(width: 13),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w600, color: AppColors.ink)),
                const SizedBox(height: 2),
                Text(sub, style: const TextStyle(fontSize: 12.5, color: AppColors.faint)),
              ],
            ),
          ),
          if (chip != null) chip,
        ],
      ),
    );
  }

  Widget _button(BuildContext context, _Hero s) {
    if (s.syncing) {
      return const PrimaryButton(label: 'Sincronizando…', icon: Icons.sync, busy: true, onPressed: null);
    }
    if (s.canReconnect) {
      return PrimaryButton(label: 'Reconectar agora', icon: Icons.bluetooth, onPressed: () => BandApi.reconnect());
    }
    return PrimaryButton(
      label: 'Atualizar dados',
      icon: Icons.sync,
      onPressed: () {
        BandApi.syncNow();
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Conectando à pulseira e baixando os dados…')),
        );
      },
    );
  }

  Widget _stuckBanner() {
    return Container(
      margin: const EdgeInsets.only(bottom: 6),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(color: AppColors.dawnBg, borderRadius: BorderRadius.circular(12), border: Border.all(color: const Color(0xFFF0DBB0))),
      child: const Row(children: [
        Icon(Icons.info_outline, color: AppColors.dawn, size: 18),
        SizedBox(width: 10),
        Expanded(
          child: Text('A pulseira parou de responder várias vezes. Se continuar, desligue e ligue o Bluetooth do celular.',
              style: TextStyle(fontSize: 12.5, color: Color(0xFF8A5A00), height: 1.4)),
        ),
      ]),
    );
  }

  _Hero _hero(AppState app) {
    final detail = (app.status['detail'] as String?) ?? '';
    switch (app.state) {
      case 'READY':
        return _Hero(
          title: 'Pulseira conectada',
          sub: app.battery >= 0 ? 'bateria ${app.battery}% · pronta' : 'pronta para sincronizar',
          dot: AppColors.ok,
          icon: Icons.watch_outlined,
          ringColor: AppColors.brand,
          ringValue: 0.8,
        );
      case 'SYNCING':
        return _Hero(
          title: 'Sincronizando…',
          sub: 'baixando dados da pulseira',
          dot: AppColors.brand,
          icon: Icons.watch_outlined,
          ringColor: AppColors.brand,
          ringValue: null,
          syncing: true,
        );
      case 'CONNECTING':
        return _Hero(title: 'Conectando…', sub: detail.isEmpty ? 'procurando a pulseira' : detail, dot: AppColors.sleep, icon: Icons.bluetooth_searching, ringColor: AppColors.sleep, ringValue: null);
      case 'AUTHENTICATING':
        return _Hero(title: 'Autenticando…', sub: detail.isEmpty ? 'confirme na pulseira se pedir' : detail, dot: AppColors.sleep, icon: Icons.lock_outline, ringColor: AppColors.sleep, ringValue: null);
      case 'INITIALIZING':
        return _Hero(title: 'Ativando monitoramentos…', sub: 'configurando a pulseira', dot: AppColors.sleep, icon: Icons.tune, ringColor: AppColors.sleep, ringValue: null);
      default:
        return _Hero(
          title: 'Desconectada',
          sub: detail.isEmpty ? 'tentando reconectar sozinho' : detail,
          dot: AppColors.danger,
          icon: Icons.watch_outlined,
          ringColor: AppColors.faint,
          ringValue: 1.0,
          canReconnect: true,
        );
    }
  }
}

class _Hero {
  final String title;
  final String sub;
  final Color dot;
  final IconData icon;
  final Color ringColor;
  final double? ringValue;
  final bool syncing;
  final bool canReconnect;

  _Hero({
    required this.title,
    required this.sub,
    required this.dot,
    required this.icon,
    required this.ringColor,
    required this.ringValue,
    this.syncing = false,
    this.canReconnect = false,
  });
}

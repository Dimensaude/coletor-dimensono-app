import 'package:flutter/material.dart';

import '../app_state.dart';
import '../band_api.dart';
import '../theme.dart';
import '../widgets.dart';
import 'consentimento_texto.dart';
import 'pair_screen.dart';

/// What the privacy screen shows for the participant code: nothing before it is released, and only the last
/// 4 digits of a numeric code (it is the patient's phone).
String participantLabel(AppState app) {
  if (!app.participantLinked || app.participant.isEmpty) return 'aguardando liberação';
  final p = app.participant;
  if (RegExp(r'^[0-9]+$').hasMatch(p)) return p.length > 4 ? '…${p.substring(p.length - 4)}' : p;
  return p;
}

/// Minimal screen: band, consent to send data, participant code, CSV export, battery.
class PrivacyScreen extends StatefulWidget {
  final AppState app;

  const PrivacyScreen({super.key, required this.app});

  @override
  State<PrivacyScreen> createState() => _PrivacyScreenState();
}

class _PrivacyScreenState extends State<PrivacyScreen> with WidgetsBindingObserver {
  bool? _batteryOk;
  bool _exporting = false;
  bool _deleting = false;

  AppState get app => widget.app;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _checkBattery();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _checkBattery();
  }

  Future<void> _checkBattery() async {
    try {
      final ok = await BandApi.batteryOptimizationIgnored();
      if (mounted) setState(() => _batteryOk = ok);
    } catch (e) {
      debugPrint('battery: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Privacidade e dados')),
      body: AnimatedBuilder(
        animation: app,
        builder: (context, _) => ListView(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 28),
          children: [
            const SectionTitle('Pulseira'),
            _deviceCard(context),
            const SizedBox(height: 18),
            const SectionTitle('Envio dos dados'),
            _group([
              SwitchListTile(
                value: app.consent,
                onChanged: _toggleConsent,
                activeColor: Colors.white,
                activeTrackColor: AppColors.brand,
                contentPadding: const EdgeInsets.symmetric(horizontal: 8),
                title: const Text('Enviar meus dados ao profissional',
                    style: TextStyle(fontSize: 14.5, fontWeight: FontWeight.w600, color: AppColors.ink)),
                subtitle: const Text('Os dados da pulseira vão ao profissional de saúde vinculado, com o código do participante.',
                    style: AppText.sub),
              ),
              _tile('Código do participante', '', participantLabel(app), null, valueColor: AppColors.muted),
            ]),
            const SizedBox(height: 18),
            const SectionTitle('Seus dados'),
            _group([
              _tile('Exportar meus dados (CSV)', 'Salva os arquivos na pasta Downloads', _exporting ? 'Exportando…' : 'Exportar',
                  _exporting ? null : _export),
              _tile('Otimização de bateria', 'Precisa estar liberada para rodar sempre', _batteryOk == true ? 'Liberado' : 'Liberar',
                  _batteryOk == true
                      ? null
                      : () async {
                          await BandApi.requestIgnoreBatteryOptimization();
                          await Future<void>.delayed(const Duration(seconds: 2));
                          _checkBattery();
                        },
                  valueColor: _batteryOk == true ? AppColors.ok : AppColors.brand),
            ]),
            const SizedBox(height: 18),
            const SectionTitle('Conta e privacidade'),
            _group([
              _tile('Política de privacidade', 'Abre no navegador', 'Abrir', () => _abrir('https://dimensono.com.br/privacidade')),
              _tile('Excluir conta pelo site', 'Abre no navegador', 'Abrir', () => _abrir('https://dimensono.com.br/exclusao')),
              if (app.hasSession)
                _tile('Excluir minha conta', 'Apaga a conta e os dados deste celular', _deleting ? 'Excluindo…' : 'Excluir',
                    _deleting ? null : _deleteAccount,
                    valueColor: AppColors.danger),
            ]),
            const SizedBox(height: 16),
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 4),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(
                  'O app só coleta e envia os dados da pulseira ao profissional de saúde vinculado. A comunicação é baseada no '
                  'protocolo aberto do Gadgetbridge (AGPL-3.0), por isso o código também é AGPL-3.0.',
                  style: TextStyle(fontSize: 12, height: 1.45, color: AppColors.faint),
                ),
                LinkTexto(texto: 'Código-fonte: github.com/Dimensaude/coletor-dimensono-app', url: urlCodigoFonte),
              ]),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _toggleConsent(bool v) async {
    if (!v) {
      final ok = await _confirm(context, 'Parar de enviar os dados?',
          'A pulseira continua guardando os dados no celular, mas nada será enviado ao profissional até você ligar de novo.');
      if (!ok) return;
    }
    try {
      await app.setConsent(v);
    } catch (e) {
      debugPrint('setConsent: $e');
    }
  }

  Future<void> _abrir(String url) async {
    var ok = false;
    try {
      ok = await BandApi.openUrl(url);
    } catch (e) {
      debugPrint('openUrl: $e');
    }
    if (!ok && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Não foi possível abrir o navegador.')));
    }
  }

  Future<void> _deleteAccount() async {
    final yes = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Excluir minha conta?'),
        content: const Text(
            'A sua conta será apagada e os dados guardados neste celular serão removidos. Isso não pode ser desfeito.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Cancelar')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
            onPressed: () => Navigator.pop(c, true),
            child: const Text('Excluir'),
          ),
        ],
      ),
    );
    if (yes != true) return;
    setState(() => _deleting = true);
    final r = await app.deleteAccount();
    if (!mounted) return;
    setState(() => _deleting = false);
    if (r == 'ok') {
      // Back to the entry flow (the phone is a fresh install again)
      Navigator.of(context).popUntil((route) => route.isFirst);
      return;
    }
    final msg = r == 'expired'
        ? 'Sua sessão expirou. Entre de novo na tela inicial ou exclua a conta pelo site.'
        : r == 'doctor'
            ? 'Esta conta é de profissional e precisa ser excluída pelo site.'
            : 'Não foi possível excluir agora. Tente de novo ou exclua a conta pelo site.';
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _export() async {
    setState(() => _exporting = true);
    String msg;
    try {
      await BandApi.exportCsv();
      msg = 'Dados exportados para a pasta Downloads.';
    } catch (e) {
      msg = 'Não foi possível exportar os dados.';
    }
    if (!mounted) return;
    setState(() => _exporting = false);
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  Widget _deviceCard(BuildContext context) {
    if (!app.paired) {
      return AppCard(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          const Text('Nenhuma pulseira pareada', style: AppText.title),
          const SizedBox(height: 12),
          PrimaryButton(
            label: 'Parear pulseira',
            icon: Icons.bluetooth_searching,
            onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => PairScreen(app: app))),
          ),
        ]),
      );
    }
    final ready = app.state == 'READY' || app.state == 'SYNCING';
    return AppCard(
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Row(children: [
          Container(
            width: 48,
            height: 48,
            decoration: BoxDecoration(color: AppColors.tint, borderRadius: BorderRadius.circular(12)),
            child: const Icon(Icons.watch_outlined, color: AppColors.brand),
          ),
          const SizedBox(width: 13),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(app.deviceName, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: AppColors.ink)),
              const SizedBox(height: 4),
              Row(children: [
                Container(width: 8, height: 8, decoration: BoxDecoration(color: ready ? AppColors.ok : AppColors.danger, shape: BoxShape.circle)),
                const SizedBox(width: 6),
                Text(ready ? 'Conectada' : _stateText(app.state), style: const TextStyle(fontSize: 12.5, color: AppColors.muted)),
              ]),
            ]),
          ),
        ]),
        const SizedBox(height: 14),
        Row(children: [
          Expanded(child: SecondaryButton(label: 'Reconectar', onPressed: () => BandApi.reconnect())),
          const SizedBox(width: 8),
          Expanded(
            child: SecondaryButton(
              label: 'Esquecer',
              color: AppColors.danger,
              onPressed: () async {
                final ok = await _confirm(context, 'Esquecer a pulseira?', 'O app desconecta e apaga as chaves de pareamento.');
                if (ok) {
                  await BandApi.forget();
                  await Future<void>.delayed(const Duration(milliseconds: 500));
                  await app.reloadStatus();
                }
              },
            ),
          ),
        ]),
      ]),
    );
  }

  Widget _group(List<Widget> children) {
    final items = <Widget>[];
    for (var i = 0; i < children.length; i++) {
      if (i > 0) items.add(const Divider(height: 1, color: AppColors.line));
      items.add(children[i]);
    }
    return AppCard(padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2), child: Column(children: items));
  }

  Widget _tile(String title, String subtitle, String value, VoidCallback? onTap, {Color valueColor = AppColors.brand}) {
    return ListTile(
      onTap: onTap,
      contentPadding: const EdgeInsets.symmetric(horizontal: 8),
      title: Text(title, style: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w600, color: AppColors.ink)),
      subtitle: subtitle.isEmpty ? null : Text(subtitle, style: AppText.sub),
      trailing: Row(mainAxisSize: MainAxisSize.min, children: [
        Flexible(
          child: Text(value,
              style: TextStyle(fontSize: 13.5, color: valueColor, fontWeight: FontWeight.w600, fontFeatures: AppText.tabular),
              overflow: TextOverflow.ellipsis),
        ),
        if (onTap != null) Icon(Icons.chevron_right, color: valueColor, size: 18),
      ]),
    );
  }

  String _stateText(String s) {
    switch (s) {
      case 'CONNECTING':
        return 'Procurando a pulseira…';
      case 'AUTHENTICATING':
        return 'Autenticando…';
      case 'INITIALIZING':
        return 'Ativando monitoramentos…';
      default:
        return 'Desconectada';
    }
  }

  Future<bool> _confirm(BuildContext context, String title, String text) async {
    final r = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: Text(text),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancelar')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Confirmar')),
        ],
      ),
    );
    return r == true;
  }
}

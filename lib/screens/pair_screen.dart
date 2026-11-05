import 'dart:async';

import 'package:flutter/material.dart';

import '../app_state.dart';
import '../band_api.dart';
import '../theme.dart';
import '../widgets.dart';

class _Found {
  final String address;
  final String name;
  int rssi;
  _Found(this.address, this.name, this.rssi);

  bool get looksLikeBand {
    final n = name.toLowerCase();
    return n.contains('band') || n.contains('huawei') || n.contains('honor');
  }
}

class PairScreen extends StatefulWidget {
  final AppState app;

  const PairScreen({super.key, required this.app});

  @override
  State<PairScreen> createState() => _PairScreenState();
}

class _PairScreenState extends State<PairScreen> {
  final Map<String, _Found> _found = {};
  StreamSubscription<Map<String, dynamic>>? _scanSub;
  bool _scanning = false;
  String? _problem;
  String? _pairingAddress;

  @override
  void initState() {
    super.initState();
    _scanSub = BandApi.scanStream().listen(_onScan);
    WidgetsBinding.instance.addPostFrameCallback((_) => _start());
  }

  @override
  void dispose() {
    _scanSub?.cancel();
    BandApi.stopScan();
    super.dispose();
  }

  void _onScan(Map<String, dynamic> e) {
    if (!mounted) return;
    if (e['done'] == true) {
      setState(() => _scanning = false);
      return;
    }
    if (e.containsKey('error')) {
      setState(() {
        _scanning = false;
        _problem = 'A busca Bluetooth falhou (código ${e['error']}). Tente de novo.';
      });
      return;
    }
    final address = e['address'] as String?;
    final name = e['name'] as String?;
    if (address == null || name == null) return;
    final rssi = (e['rssi'] as num?)?.toInt() ?? -100;
    setState(() {
      final f = _found[address];
      if (f == null) {
        _found[address] = _Found(address, name, rssi);
      } else {
        f.rssi = rssi;
      }
    });
  }

  Future<void> _start() async {
    setState(() => _problem = null);
    final granted = await BandApi.requestPermissions();
    if (!granted) {
      setState(() => _problem = 'Sem permissão de Bluetooth não é possível encontrar a pulseira. '
          'Libere "Dispositivos próximos" nas configurações do app.');
      return;
    }
    if (!await BandApi.bluetoothEnabled()) {
      await BandApi.enableBluetooth();
      setState(() => _problem = 'Ligue o Bluetooth e toque em "Buscar de novo".');
      return;
    }
    final ok = await BandApi.startScan();
    setState(() {
      _scanning = ok;
      if (!ok) _problem = 'Não foi possível iniciar a busca Bluetooth.';
    });
  }

  Future<void> _pair(_Found f) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Parear ${marcaPulseira(f.name)}?'),
        content: const Text(
          'Na primeira conexão a pulseira pode pedir confirmação na tela. '
          'Toque em confirmar no relógio quando aparecer.\n\n'
          'Depois do pareamento, o app ativa automaticamente os monitoramentos e passa a '
          'baixar e enviar os dados.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancelar')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Parear')),
        ],
      ),
    );
    if (confirm != true) return;
    await BandApi.stopScan();
    await BandApi.pair(f.address, f.name);
    setState(() {
      _pairingAddress = f.address;
      _scanning = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Parear pulseira')),
      body: AnimatedBuilder(
        animation: widget.app,
        builder: (context, _) => _pairingAddress != null ? _progress(context) : _list(context),
      ),
    );
  }

  Widget _list(BuildContext context) {
    final items = _found.values.toList()
      ..sort((a, b) {
        if (a.looksLikeBand != b.looksLikeBand) return a.looksLikeBand ? -1 : 1;
        return b.rssi.compareTo(a.rssi);
      });
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Antes de começar', style: AppText.title),
              SizedBox(height: 8),
              Text(
                '1. Se a pulseira Actband já foi usada em outro celular ou app (como o app de saúde que veio com ela), desvincule-a lá ou redefina a pulseira: ela só se conecta a um celular por vez.\n'
                '2. Permita o que o Android pedir (veja abaixo).\n'
                '3. Deixe a pulseira perto do celular, com a tela acesa.\n'
                '4. Toque no nome dela na lista abaixo.\n\n'
                'Permitir: o Android vai pedir Bluetooth/dispositivos próximos (obrigatório; em Android antigo '
                'o sistema chama isso de localização) e notificações (para o app avisar que está coletando).',
                style: AppText.sub,
              ),
            ],
          ),
        ),
        const SizedBox(height: 18),
        Row(children: [
          const Expanded(child: SectionTitle('Dispositivos encontrados')),
          if (_scanning)
            const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.brand))
          else
            TextButton(onPressed: _start, child: const Text('Buscar de novo')),
        ]),
        if (_problem != null) ...[
          const SizedBox(height: 4),
          Text(_problem!, style: const TextStyle(color: AppColors.danger, fontSize: 13)),
        ],
        const SizedBox(height: 8),
        if (items.isEmpty && !_scanning)
          const EmptyNote('Nenhum dispositivo encontrado. Confira se a pulseira está ligada e desvinculada de outro celular ou app.')
        else
          for (final f in items)
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: Material(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(14),
                child: InkWell(
                  borderRadius: BorderRadius.circular(14),
                  onTap: () => _pair(f),
                  child: Container(
                    decoration: BoxDecoration(borderRadius: BorderRadius.circular(14), border: Border.all(color: AppColors.line)),
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                    child: Row(children: [
                      Container(
                        width: 44,
                        height: 44,
                        decoration: BoxDecoration(color: AppColors.tint, borderRadius: BorderRadius.circular(12)),
                        child: Icon(f.looksLikeBand ? Icons.watch_outlined : Icons.bluetooth,
                            color: f.looksLikeBand ? AppColors.brand : AppColors.muted, size: 22),
                      ),
                      const SizedBox(width: 14),
                      Expanded(
                        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          Text(marcaPulseira(f.name), style: const TextStyle(color: AppColors.ink, fontSize: 15, fontWeight: FontWeight.w600)),
                          const SizedBox(height: 2),
                          Text('${f.address} · ${f.rssi} dBm', style: AppText.sub),
                        ]),
                      ),
                      const Icon(Icons.chevron_right, color: AppColors.muted),
                    ]),
                  ),
                ),
              ),
            ),
      ],
    );
  }

  Widget _progress(BuildContext context) {
    final state = widget.app.state;
    final detail = (widget.app.status['detail'] as String?) ?? '';
    final authError = widget.app.status['authError'] as String?;
    final done = state == 'READY' || state == 'SYNCING';
    String title;
    String text;
    switch (state) {
      case 'CONNECTING':
        title = 'Conectando…';
        text = 'Procurando a pulseira. Mantenha-a perto do celular.';
        break;
      case 'AUTHENTICATING':
        title = 'Confirme na pulseira';
        text = detail.isNotEmpty ? detail : 'Se aparecer um pedido de pareamento na tela da Band 10, confirme.';
        break;
      case 'INITIALIZING':
        title = 'Ativando monitoramentos…';
        text = 'Configurando a pulseira para a coleta.';
        break;
      case 'READY':
      case 'SYNCING':
        title = 'Pulseira pareada';
        text = 'Pronto. O app já conecta sozinho e envia os dados ao servidor.';
        break;
      default:
        title = 'Desconectada';
        text = detail.isNotEmpty ? detail : 'A conexão caiu. O app vai tentar de novo automaticamente.';
    }
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Icon(done ? Icons.check_circle : Icons.watch_outlined, size: 64, color: done ? AppColors.ok : AppColors.brand),
          const SizedBox(height: 18),
          Text(title, textAlign: TextAlign.center, style: const TextStyle(fontFamily: AppText.serif, fontSize: 22, fontWeight: FontWeight.w700, color: AppColors.ink)),
          const SizedBox(height: 10),
          Text(text, textAlign: TextAlign.center, style: AppText.sub.copyWith(fontSize: 14)),
          if (authError != null && authError.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text(authError, textAlign: TextAlign.center, style: const TextStyle(color: AppColors.danger)),
          ],
          const SizedBox(height: 24),
          if (!done)
            const Center(child: CircularProgressIndicator(color: AppColors.brand))
          else
            PrimaryButton(label: 'Concluir', icon: Icons.check, onPressed: () => Navigator.of(context).pop()),
          const SizedBox(height: 12),
          TextButton(
            onPressed: () => setState(() {
              _pairingAddress = null;
              _found.clear();
              _start();
            }),
            child: const Text('Escolher outro dispositivo'),
          ),
        ],
      ),
    );
  }
}

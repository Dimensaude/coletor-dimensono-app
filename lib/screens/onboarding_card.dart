import 'package:flutter/material.dart';

import '../app_state.dart';
import '../theme.dart';
import '../widgets.dart';
import 'consentimento_texto.dart';

/// Consent card for installs from 1.5/1.6 that were linked by a link and have not answered yet.
/// New installs use the entry flow (screens/entrada) instead.
class OnboardingCard extends StatefulWidget {
  final AppState app;

  const OnboardingCard({super.key, required this.app});

  @override
  State<OnboardingCard> createState() => _OnboardingCardState();
}

class _OnboardingCardState extends State<OnboardingCard> {
  late bool _agree = widget.app.consent;
  bool _busy = false;
  String? _error;

  AppState get app => widget.app;

  bool get _canContinue => _agree && !_busy;

  Future<void> _continue() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    var ok = true;
    try {
      ok = await app.setConsent(true);
    } catch (_) {
      ok = false;
    }
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (!ok) _error = 'Não consegui salvar. Tente de novo.';
    });
  }

  @override
  Widget build(BuildContext context) {
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text('Antes de começar', style: AppText.title),
          const SizedBox(height: 14),
          if (app.linkError.isNotEmpty) ...[
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(color: AppColors.dawnBg, borderRadius: BorderRadius.circular(12)),
              child: Row(children: [
                const Icon(Icons.info_outline, color: AppColors.dawn, size: 18),
                const SizedBox(width: 10),
                Expanded(child: Text(app.linkError, style: const TextStyle(fontSize: 12.5, color: Color(0xFF8A5A00), height: 1.4))),
              ]),
            ),
            const SizedBox(height: 12),
          ],
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(color: AppColors.okBg, borderRadius: BorderRadius.circular(12)),
            child: Row(children: [
              const Icon(Icons.check_circle_outline, color: AppColors.ok, size: 20),
              const SizedBox(width: 10),
              Expanded(
                child: Text('Vinculado ao código ${app.participant}',
                    style: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w600, color: AppColors.ink)),
              ),
            ]),
          ),
          const SizedBox(height: 16),
          const ConsentimentoTexto(),
          const SizedBox(height: 6),
          CheckboxListTile(
            value: _agree,
            onChanged: _busy ? null : (v) => setState(() => _agree = v ?? false),
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            activeColor: AppColors.brand,
            title: const Text(textoConcordo,
                style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.ink)),
          ),
          if (_error != null) ...[
            const SizedBox(height: 4),
            Text(_error!, style: const TextStyle(fontSize: 12.5, color: AppColors.danger)),
          ],
          const SizedBox(height: 12),
          PrimaryButton(
            label: 'Continuar',
            icon: Icons.arrow_forward,
            busy: _busy,
            onPressed: _canContinue ? _continue : null,
          ),
        ],
      ),
    );
  }
}

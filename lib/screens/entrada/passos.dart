import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../dimensono_api.dart';
import '../consentimento_texto.dart';
import '../../theme.dart';
import '../../widgets.dart';

/// Widgets of each step of the entry flow. They hold only what the user types; the flow decides what happens.

const TextStyle _campo = TextStyle(fontSize: 16, color: AppColors.ink);

class PassoTitulo extends StatelessWidget {
  final String titulo;
  final String? texto;

  const PassoTitulo(this.titulo, {super.key, this.texto});

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(titulo, style: const TextStyle(fontFamily: AppText.serif, fontSize: 23, fontWeight: FontWeight.w700, color: AppColors.ink)),
      if (texto != null) ...[
        const SizedBox(height: 8),
        Text(texto!, style: const TextStyle(fontSize: 14.5, color: AppColors.muted, height: 1.45)),
      ],
      const SizedBox(height: 18),
    ]);
  }
}

class PassoErro extends StatelessWidget {
  final String? texto;

  const PassoErro(this.texto, {super.key});

  @override
  Widget build(BuildContext context) {
    if (texto == null || texto!.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Text(texto!, style: const TextStyle(fontSize: 13.5, color: AppColors.danger, height: 1.4)),
    );
  }
}

class PassoAviso extends StatelessWidget {
  final String texto;

  const PassoAviso(this.texto, {super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 14),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(color: AppColors.dawnBg, borderRadius: BorderRadius.circular(12)),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Icon(Icons.info_outline, color: AppColors.dawn, size: 18),
        const SizedBox(width: 10),
        Expanded(child: Text(texto, style: const TextStyle(fontSize: 13, color: Color(0xFF8A5A00), height: 1.4))),
      ]),
    );
  }
}

String soDigitos(String s) => s.replaceAll(RegExp(r'[^0-9]'), '');

// ------------------------------------------------------------------ telefone

class PassoTelefone extends StatefulWidget {
  final String inicial;
  final bool busy;
  final String? erro;
  final String? aviso;
  final void Function(String telefone) onSubmit;

  const PassoTelefone({super.key, this.inicial = '', required this.busy, this.erro, this.aviso, required this.onSubmit});

  @override
  State<PassoTelefone> createState() => _PassoTelefoneState();
}

class _PassoTelefoneState extends State<PassoTelefone> {
  late final TextEditingController _c = TextEditingController(text: widget.inicial);

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  bool get _ok {
    final n = soDigitos(_c.text).length;
    return n >= 10 && n <= 15;
  }

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const PassoTitulo('Qual é o seu celular?',
          texto: 'Vamos enviar um código de 6 números pelo WhatsApp para confirmar que o número é seu.'),
      if (widget.aviso != null) PassoAviso(widget.aviso!),
      const Text('Celular com DDD', style: AppText.label),
      const SizedBox(height: 6),
      TextField(
        controller: _c,
        keyboardType: TextInputType.phone,
        autofillHints: const [AutofillHints.telephoneNumber],
        style: _campo,
        inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9 ()+\-]'))],
        decoration: const InputDecoration(hintText: '(11) 91234-5678'),
        onChanged: (_) => setState(() {}),
      ),
      const SizedBox(height: 14),
      PassoErro(widget.erro),
      PrimaryButton(
        label: 'Enviar código',
        icon: Icons.arrow_forward,
        busy: widget.busy,
        onPressed: _ok ? () => widget.onSubmit(soDigitos(_c.text)) : null,
      ),
    ]);
  }
}

// ------------------------------------------------------------------ código do WhatsApp

class PassoCodigo extends StatefulWidget {
  final String telefone;
  final String? codigoAutomatico;
  final bool busy;
  final String? erro;
  final void Function(String codigo) onSubmit;
  final VoidCallback onVoltar;
  final VoidCallback onReenviar;

  const PassoCodigo({
    super.key,
    required this.telefone,
    this.codigoAutomatico,
    required this.busy,
    this.erro,
    required this.onSubmit,
    required this.onVoltar,
    required this.onReenviar,
  });

  @override
  State<PassoCodigo> createState() => _PassoCodigoState();
}

class _PassoCodigoState extends State<PassoCodigo> {
  late final TextEditingController _c = TextEditingController(text: widget.codigoAutomatico ?? '');

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  bool get _ok => soDigitos(_c.text).length == 6;

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      PassoTitulo('Digite o código', texto: 'Enviamos um código de 6 números para o WhatsApp do número ${widget.telefone}.'),
      const Text('Código de 6 números', style: AppText.label),
      const SizedBox(height: 6),
      TextField(
        controller: _c,
        keyboardType: TextInputType.number,
        autofillHints: const [AutofillHints.oneTimeCode],
        style: _campo.copyWith(letterSpacing: 4),
        inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(6)],
        decoration: const InputDecoration(hintText: '000000', counterText: ''),
        onChanged: (_) => setState(() {}),
      ),
      const SizedBox(height: 8),
      const Text('Não chegou? Confira o número; você pode pedir outro código em instantes.', style: AppText.sub),
      const SizedBox(height: 14),
      PassoErro(widget.erro),
      PrimaryButton(
        label: 'Confirmar',
        icon: Icons.check,
        busy: widget.busy,
        onPressed: _ok ? () => widget.onSubmit(_c.text) : null,
      ),
      const SizedBox(height: 8),
      Row(children: [
        Expanded(child: TextButton(onPressed: widget.busy ? null : widget.onReenviar, child: const Text('Pedir outro código'))),
        Expanded(child: TextButton(onPressed: widget.busy ? null : widget.onVoltar, child: const Text('Trocar o número'))),
      ]),
    ]);
  }
}

// ------------------------------------------------------------------ senha (telefone de profissional)

class PassoSenha extends StatefulWidget {
  final bool busy;
  final String? erro;
  final void Function(String senha) onSubmit;
  final VoidCallback onVoltar;

  const PassoSenha({super.key, required this.busy, this.erro, required this.onSubmit, required this.onVoltar});

  @override
  State<PassoSenha> createState() => _PassoSenhaState();
}

class _PassoSenhaState extends State<PassoSenha> {
  final TextEditingController _c = TextEditingController();

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const PassoTitulo('Digite a sua senha', texto: 'Este número tem uma conta com senha. Use a senha que você já cadastrou.'),
      const Text('Senha', style: AppText.label),
      const SizedBox(height: 6),
      TextField(
        controller: _c,
        obscureText: true,
        autocorrect: false,
        enableSuggestions: false,
        style: _campo,
        decoration: const InputDecoration(hintText: 'Sua senha'),
        onChanged: (_) => setState(() {}),
      ),
      const SizedBox(height: 14),
      PassoErro(widget.erro),
      PrimaryButton(
        label: 'Entrar',
        icon: Icons.login,
        busy: widget.busy,
        onPressed: _c.text.isEmpty ? null : () => widget.onSubmit(_c.text),
      ),
      const SizedBox(height: 8),
      TextButton(onPressed: widget.busy ? null : widget.onVoltar, child: const Text('Usar outro número')),
    ]);
  }
}

// ------------------------------------------------------------------ código do profissional

final RegExp _profPattern = RegExp(r'^DMS-[A-Z0-9]{8,16}$');

String normalizaProfissional(String raw) => raw.trim().toUpperCase();
bool profissionalValido(String raw) => _profPattern.hasMatch(normalizaProfissional(raw));

class PassoProfissional extends StatefulWidget {
  final String inicial;
  final bool busy;
  final String? erro;
  final String? aviso;
  final void Function(String codigo) onSubmit;

  const PassoProfissional({super.key, this.inicial = '', required this.busy, this.erro, this.aviso, required this.onSubmit});

  @override
  State<PassoProfissional> createState() => _PassoProfissionalState();
}

class _PassoProfissionalState extends State<PassoProfissional> {
  late final TextEditingController _c = TextEditingController(text: widget.inicial);

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  String? get _dica {
    if (_c.text.trim().isEmpty || profissionalValido(_c.text)) return null;
    return 'O código começa com DMS- e tem de 8 a 16 letras e números.';
  }

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const PassoTitulo('Código do profissional', texto: 'Digite o código que o seu profissional de saúde enviou. Ele começa com DMS-.'),
      if (widget.aviso != null) PassoAviso(widget.aviso!),
      const Text('Código do profissional', style: AppText.label),
      const SizedBox(height: 6),
      TextField(
        controller: _c,
        autocorrect: false,
        enableSuggestions: false,
        textCapitalization: TextCapitalization.characters,
        style: _campo,
        decoration: InputDecoration(hintText: 'DMS-XXXXXXXX', errorText: _dica, errorMaxLines: 3),
        onChanged: (_) => setState(() {}),
      ),
      const SizedBox(height: 14),
      PassoErro(widget.erro),
      PrimaryButton(
        label: 'Continuar',
        icon: Icons.arrow_forward,
        busy: widget.busy,
        onPressed: profissionalValido(_c.text) ? () => widget.onSubmit(normalizaProfissional(_c.text)) : null,
      ),
    ]);
  }
}

class PassoConfirmaProfissional extends StatelessWidget {
  final Professional prof;
  final bool busy;
  final String? erro;
  final VoidCallback onSim;
  final VoidCallback onNao;

  const PassoConfirmaProfissional({super.key, required this.prof, required this.busy, this.erro, required this.onSim, required this.onNao});

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const PassoTitulo('É este profissional?'),
      AppCard(
        child: Row(children: [
          Container(
            width: 46,
            height: 46,
            decoration: BoxDecoration(color: AppColors.tint, borderRadius: BorderRadius.circular(12)),
            child: const Icon(Icons.medical_services_outlined, color: AppColors.brand),
          ),
          const SizedBox(width: 13),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(prof.display, style: const TextStyle(fontSize: 16.5, fontWeight: FontWeight.w700, color: AppColors.ink)),
              if (prof.clinicName.isNotEmpty) ...[
                const SizedBox(height: 3),
                Text(prof.clinicName, style: const TextStyle(fontSize: 14, color: AppColors.muted)),
              ],
            ]),
          ),
        ]),
      ),
      const SizedBox(height: 16),
      PassoErro(erro),
      PrimaryButton(label: 'Sim, é este', icon: Icons.check, busy: busy, onPressed: onSim),
      const SizedBox(height: 8),
      TextButton(onPressed: busy ? null : onNao, child: const Text('Não, corrigir o código')),
    ]);
  }
}

class PassoVinculado extends StatelessWidget {
  final Professional prof;
  final String? aviso;
  final VoidCallback onContinuar;
  final VoidCallback onTrocar;

  const PassoVinculado({super.key, required this.prof, this.aviso, required this.onContinuar, required this.onTrocar});

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      PassoTitulo('Você está vinculado a ${prof.display}',
          texto: prof.clinicName.isEmpty ? null : prof.clinicName),
      if (aviso != null) PassoAviso(aviso!),
      PrimaryButton(label: 'Continuar', icon: Icons.arrow_forward, onPressed: onContinuar),
      const SizedBox(height: 8),
      TextButton(onPressed: onTrocar, child: const Text('Trocar profissional')),
    ]);
  }
}

// ------------------------------------------------------------------ dados do paciente

class DadosPaciente {
  final String nome;
  final String nascimentoIso;
  final String sexo;
  const DadosPaciente(this.nome, this.nascimentoIso, this.sexo);
}

/// Validates the birth date typed as day/month/year. Returns the ISO date (YYYY-MM-DD) or null.
String? nascimentoIso(String dia, String mes, String ano, {DateTime? hoje}) {
  final d = int.tryParse(dia), m = int.tryParse(mes), a = int.tryParse(ano);
  if (d == null || m == null || a == null || ano.length != 4 || a < 1900) return null;
  final dt = DateTime(a, m, d);
  if (dt.year != a || dt.month != m || dt.day != d) return null;
  final now = hoje ?? DateTime.now();
  if (dt.isAfter(DateTime(now.year, now.month, now.day))) return null;
  String two(int n) => n < 10 ? '0$n' : '$n';
  return '$a-${two(m)}-${two(d)}';
}

/// The server creates accounts with a placeholder name (`wpp_<phone>`, `user_<id>`) until the patient registers: that
/// is never shown as if it were the patient's name.
String nomeParaPreencher(String? nome) {
  final n = (nome ?? '').trim();
  final l = n.toLowerCase();
  return l.startsWith('wpp_') || l.startsWith('user_') ? '' : n;
}

class PassoDados extends StatefulWidget {
  final String nomeInicial;
  final String nascimentoInicial; // DD/MM/YYYY
  final String sexoInicial; // F, M, I or empty
  final bool busy;
  final List<String> erros;
  final String? aviso;
  final void Function(DadosPaciente dados) onSubmit;

  const PassoDados({
    super.key,
    this.nomeInicial = '',
    this.nascimentoInicial = '',
    this.sexoInicial = '',
    required this.busy,
    this.erros = const [],
    this.aviso,
    required this.onSubmit,
  });

  @override
  State<PassoDados> createState() => _PassoDadosState();
}

class _PassoDadosState extends State<PassoDados> {
  late final TextEditingController _nome = TextEditingController(text: nomeParaPreencher(widget.nomeInicial));
  late final TextEditingController _dia;
  late final TextEditingController _mes;
  late final TextEditingController _ano;
  late String _sexo = const ['F', 'M', 'I'].contains(widget.sexoInicial) ? widget.sexoInicial : '';
  String? _local;

  @override
  void initState() {
    super.initState();
    final p = widget.nascimentoInicial.split('/');
    final ok = p.length == 3;
    _dia = TextEditingController(text: ok ? p[0] : '');
    _mes = TextEditingController(text: ok ? p[1] : '');
    _ano = TextEditingController(text: ok ? p[2] : '');
  }

  @override
  void dispose() {
    _nome.dispose();
    _dia.dispose();
    _mes.dispose();
    _ano.dispose();
    super.dispose();
  }

  void _enviar() {
    final nome = _nome.text.trim().replaceAll(RegExp(r'\s+'), ' ');
    if (nome.split(' ').length < 2) {
      setState(() => _local = 'Informe o nome completo (nome e sobrenome).');
      return;
    }
    final iso = nascimentoIso(_dia.text.trim(), _mes.text.trim(), _ano.text.trim());
    if (iso == null) {
      setState(() => _local = 'Confira a data de nascimento (dia, mês e ano com 4 números, sem data futura).');
      return;
    }
    if (_sexo.isEmpty) {
      setState(() => _local = 'Escolha o sexo ao nascer.');
      return;
    }
    setState(() => _local = null);
    widget.onSubmit(DadosPaciente(nome, iso, _sexo));
  }

  Widget _num(TextEditingController c, String rotulo, int max) {
    return TextField(
      controller: c,
      keyboardType: TextInputType.number,
      textAlign: TextAlign.center,
      style: _campo,
      inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(max)],
      decoration: InputDecoration(labelText: rotulo, counterText: '', contentPadding: const EdgeInsets.symmetric(horizontal: 8, vertical: 14)),
      onChanged: (_) => setState(() => _local = null),
    );
  }

  @override
  Widget build(BuildContext context) {
    final erro = _local ?? (widget.erros.isEmpty ? null : widget.erros.join('\n'));
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const PassoTitulo('Seus dados', texto: 'O profissional usa estes dados para identificar você.'),
      if (widget.aviso != null) PassoAviso(widget.aviso!),
      const Text('Nome completo', style: AppText.label),
      const SizedBox(height: 6),
      TextField(
        controller: _nome,
        textCapitalization: TextCapitalization.words,
        autofillHints: const [AutofillHints.name],
        style: _campo,
        decoration: const InputDecoration(hintText: 'Nome e sobrenome'),
        onChanged: (_) => setState(() => _local = null),
      ),
      const SizedBox(height: 14),
      const Text('Data de nascimento', style: AppText.label),
      const SizedBox(height: 6),
      Row(children: [
        Expanded(flex: 2, child: _num(_dia, 'Dia', 2)),
        const SizedBox(width: 8),
        Expanded(flex: 2, child: _num(_mes, 'Mês', 2)),
        const SizedBox(width: 8),
        Expanded(flex: 3, child: _num(_ano, 'Ano', 4)),
      ]),
      const SizedBox(height: 14),
      const Text('Sexo ao nascer', style: AppText.label),
      const SizedBox(height: 6),
      Wrap(spacing: 8, runSpacing: 8, children: [
        for (final o in const [('F', 'Feminino'), ('M', 'Masculino'), ('I', 'Intersexo')])
          ChoiceChip(
            label: Text(o.$2, style: const TextStyle(fontSize: 15)),
            selected: _sexo == o.$1,
            onSelected: (_) => setState(() {
              _sexo = o.$1;
              _local = null;
            }),
          ),
      ]),
      const SizedBox(height: 18),
      PassoErro(erro),
      PrimaryButton(label: 'Continuar', icon: Icons.arrow_forward, busy: widget.busy, onPressed: _enviar),
    ]);
  }
}

// ------------------------------------------------------------------ consentimento

class PassoConsentimento extends StatefulWidget {
  final bool busy;
  final String? aviso;
  final VoidCallback onSubmit;

  const PassoConsentimento({super.key, required this.busy, this.aviso, required this.onSubmit});

  @override
  State<PassoConsentimento> createState() => _PassoConsentimentoState();
}

class _PassoConsentimentoState extends State<PassoConsentimento> {
  bool _agree = false;

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const PassoTitulo('Antes de começar'),
      if (widget.aviso != null) PassoAviso(widget.aviso!),
      const ConsentimentoTexto(),
      const SizedBox(height: 6),
      CheckboxListTile(
        value: _agree,
        onChanged: widget.busy ? null : (v) => setState(() => _agree = v ?? false),
        contentPadding: EdgeInsets.zero,
        controlAffinity: ListTileControlAffinity.leading,
        activeColor: AppColors.brand,
        title: const Text(textoConcordo,
            style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.ink)),
      ),
      const SizedBox(height: 12),
      PrimaryButton(label: 'Concluir', icon: Icons.check, busy: widget.busy, onPressed: _agree ? widget.onSubmit : null),
    ]);
  }
}

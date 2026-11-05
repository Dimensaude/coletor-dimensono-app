import 'package:flutter/material.dart';

import '../band_api.dart';
import '../theme.dart';

const urlPoliticaPrivacidade = 'https://dimensono.com.br/privacidade';
const urlCodigoFonte = 'https://github.com/Dimensaude/coletor-dimensono-app';

/// Abre um link no navegador pelo canal nativo; avisa se não der.
Future<void> abrirLink(BuildContext context, String url) async {
  var ok = false;
  try {
    ok = await BandApi.openUrl(url);
  } catch (e) {
    debugPrint('openUrl: $e');
  }
  if (!ok && context.mounted) {
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Não foi possível abrir o navegador.')));
  }
}

/// Link de texto tocável (área mínima de 48 px de altura).
class LinkTexto extends StatelessWidget {
  final String texto;
  final String url;

  const LinkTexto({super.key, required this.texto, required this.url});

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.centerLeft,
      child: InkWell(
        onTap: () => abrirLink(context, url),
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 44),
          child: Align(
            widthFactor: 1,
            alignment: Alignment.centerLeft,
            child: Text(texto,
                style: const TextStyle(
                    fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.brand, decoration: TextDecoration.underline)),
          ),
        ),
      ),
    );
  }
}

/// Texto do consentimento (LGPD), alinhado à seção 14 da política de privacidade.
class ConsentimentoTexto extends StatelessWidget {
  const ConsentimentoTexto({super.key});

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const Text(
        'O app lê os dados da pulseira e os envia, com o código do seu celular, ao profissional de saúde '
        'vinculado pelo código DMS. Os dados são:',
        style: AppText.sub,
      ),
      const SizedBox(height: 6),
      for (final item in const [
        'passos, atividade, calorias e distância;',
        'sono e seus estágios;',
        'batimentos, variabilidade cardíaca, oxigenação, estresse, temperatura, respiração no sono e estado emocional estimado;',
        'treinos, com o trajeto de GPS registrado pela pulseira;',
        'ECG e arritmia, em formato bruto (sem interpretação).',
      ])
        Padding(
          padding: const EdgeInsets.only(left: 6, bottom: 3),
          child: Text('•  $item', style: AppText.sub),
        ),
      const SizedBox(height: 6),
      const Text('Você pode desligar o envio, exportar seus dados ou excluir a conta em Privacidade e dados.',
          style: AppText.sub),
      const LinkTexto(texto: 'Política de privacidade', url: urlPoliticaPrivacidade),
    ]);
  }
}

const textoConcordo = 'Concordo em enviar esses dados ao profissional vinculado';

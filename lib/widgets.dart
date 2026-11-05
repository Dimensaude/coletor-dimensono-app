import 'package:flutter/material.dart';

import 'theme.dart';

class AppCard extends StatelessWidget {
  final Widget child;
  final EdgeInsets padding;

  const AppCard({super.key, required this.child, this.padding = const EdgeInsets.all(18)});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: padding,
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: AppColors.line),
        boxShadow: const [BoxShadow(color: Color(0x0A1F2A56), blurRadius: 10, offset: Offset(0, 2))],
      ),
      child: Material(type: MaterialType.transparency, child: child),
    );
  }
}

class SectionTitle extends StatelessWidget {
  final String text;

  const SectionTitle(this.text, {super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(left: 4, bottom: 9, top: 2),
      child: Text(text.toUpperCase(), style: AppText.section),
    );
  }
}

class EmptyNote extends StatelessWidget {
  final String text;

  const EmptyNote(this.text, {super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      alignment: Alignment.center,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: AppColors.tint, borderRadius: BorderRadius.circular(12)),
      child: Text(text, textAlign: TextAlign.center, style: AppText.sub),
    );
  }
}

class PrimaryButton extends StatelessWidget {
  final String label;
  final IconData icon;
  final VoidCallback? onPressed;
  final bool busy;

  const PrimaryButton({super.key, required this.label, required this.icon, this.onPressed, this.busy = false});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 56,
      child: FilledButton.icon(
        onPressed: busy ? null : onPressed,
        style: FilledButton.styleFrom(
          backgroundColor: AppColors.brand,
          foregroundColor: Colors.white,
          disabledBackgroundColor: AppColors.tint,
          disabledForegroundColor: AppColors.muted,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(15)),
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
        ),
        icon: busy
            ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2.4, color: AppColors.muted))
            : Icon(icon, size: 21),
        label: Text(label),
      ),
    );
  }
}

class SecondaryButton extends StatelessWidget {
  final String label;
  final IconData? icon;
  final VoidCallback? onPressed;
  final Color color;

  const SecondaryButton({super.key, required this.label, this.icon, this.onPressed, this.color = AppColors.ink});

  @override
  Widget build(BuildContext context) {
    final style = OutlinedButton.styleFrom(
      foregroundColor: color,
      side: BorderSide(color: color == AppColors.danger ? const Color(0xFFE6C6CE) : AppColors.lineStrong),
      backgroundColor: AppColors.surface,
      minimumSize: const Size(44, 46),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
    );
    if (icon == null) {
      return OutlinedButton(onPressed: onPressed, style: style, child: Text(label));
    }
    return OutlinedButton.icon(onPressed: onPressed, style: style, icon: Icon(icon, size: 18), label: Text(label));
  }
}

/// Small coloured status chip used on the main screen rows.
class StatusChip extends StatelessWidget {
  final String text;
  final Color fg;
  final Color bg;

  const StatusChip(this.text, {super.key, this.fg = AppColors.ok, this.bg = AppColors.okBg});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(999)),
      child: Text(text, style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w700, color: fg)),
    );
  }
}

/// Brand logo tile (rounded square with a watch glyph).
class BrandMark extends StatelessWidget {
  final double size;
  const BrandMark({super.key, this.size = 34});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: AppColors.brand,
        borderRadius: BorderRadius.circular(size * 0.3),
        boxShadow: const [BoxShadow(color: Color(0x662E4FA3), blurRadius: 10, offset: Offset(0, 4))],
      ),
      child: Icon(Icons.watch_outlined, size: size * 0.56, color: Colors.white),
    );
  }
}

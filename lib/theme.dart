
import 'package:flutter/material.dart';

/// Dimensono brand palette (light, clinical). Mirrors the report and landing pages.
class AppColors {
  static const paper = Color(0xFFF5F7FC);
  static const surface = Color(0xFFFFFFFF);
  static const ink = Color(0xFF1C2340);
  static const muted = Color(0xFF5B6480);
  static const faint = Color(0xFF7A83A0);
  static const line = Color(0xFFDDE3F2);
  static const lineStrong = Color(0xFFC5CFE8);
  static const tint = Color(0xFFEEF2FB);
  static const brand = Color(0xFF2E4FA3);
  static const brandInk = Color(0xFF233E86);
  static const sleep = Color(0xFF9DB0F5);
  static const ok = Color(0xFF1F7A4D);
  static const okBg = Color(0xFFE8F3EC);
  static const dawn = Color(0xFFB45309);
  static const dawnBg = Color(0xFFFDF3E0);
  static const danger = Color(0xFFD2445B);
}

class AppText {
  static const List<FontFeature> tabular = [FontFeature.tabularFigures()];

  static const serif = 'SourceSerif';

  static const title = TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: AppColors.ink);
  static const h1 = TextStyle(fontFamily: serif, fontSize: 25, fontWeight: FontWeight.w700, color: AppColors.ink, letterSpacing: -0.2);
  static const section =
      TextStyle(fontSize: 11.5, fontWeight: FontWeight.w700, letterSpacing: 0.9, color: AppColors.faint);
  static const sub = TextStyle(fontSize: 12.5, color: AppColors.muted, height: 1.4);
  static const label = TextStyle(fontSize: 11.5, color: AppColors.muted);
}

ThemeData buildTheme() {
  const scheme = ColorScheme.light(
    primary: AppColors.brand,
    onPrimary: Colors.white,
    surface: AppColors.surface,
    onSurface: AppColors.ink,
  );
  return ThemeData(
    useMaterial3: true,
    brightness: Brightness.light,
    fontFamily: 'Jakarta',
    scaffoldBackgroundColor: AppColors.paper,
    colorScheme: scheme,
    dividerColor: AppColors.line,
    appBarTheme: const AppBarTheme(
      backgroundColor: AppColors.paper,
      surfaceTintColor: Colors.transparent,
      foregroundColor: AppColors.ink,
      elevation: 0,
      centerTitle: false,
      titleTextStyle: TextStyle(fontFamily: AppText.serif, fontSize: 20, fontWeight: FontWeight.w700, color: AppColors.ink),
    ),
    snackBarTheme: const SnackBarThemeData(
      backgroundColor: AppColors.ink,
      contentTextStyle: TextStyle(color: Colors.white),
      behavior: SnackBarBehavior.floating,
    ),
    progressIndicatorTheme: const ProgressIndicatorThemeData(color: AppColors.brand),
  );
}

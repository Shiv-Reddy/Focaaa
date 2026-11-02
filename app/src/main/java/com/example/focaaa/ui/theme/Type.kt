package com.example.focaaa.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    headlineMedium = TextStyle(fontSize = 32.sp, lineHeight = 38.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8f).sp),
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5f).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3f).sp),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp,
        fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Medium)
)

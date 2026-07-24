package me.thenano.yamibo.yamibo_app.thread.reader.components.post.impl

import me.thenano.yamibo.yamibo_app.i18n.i18n

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.littlesurvival.dto.page.RateBlock
import me.thenano.yamibo.yamibo_app.components.theme.YamiboTheme

@Composable
fun RateRenderer(
    rateBlock: RateBlock,
    rateRange: IntRange? = null,
    onShowAllRatings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = YamiboTheme.colors
    if (rateBlock.rates.isEmpty()) return
    val visibleRates = rateRange
        ?.let { range -> rateBlock.rates.filterIndexed { index, _ -> index in range } }
        ?: rateBlock.rates

    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.creamBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = i18n("🌟 本帖評分 (共 {} 人)", rateBlock.rateParticipatePeople),
                    color = colors.brownPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = i18n("總積分 +{}", rateBlock.rateTotalScore.toString()),
                    color = Color(0xFFD32F2F), // Standard red point color for total
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            // Divider
            HorizontalDivider(Modifier, thickness = 0.5.dp, color = colors.brownLight.copy(alpha = 0.5f))

            // Rates List
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                visibleRates.forEach { rate ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = rate.userName,
                            color = colors.textDark.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(0.3f)
                        )
                        Text(
                            text = "+${rate.score}",
                            color = Color(0xFFD32F2F),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(0.15f)
                        )
                        Text(
                            text = rate.reason ?: "",
                            color = colors.textDark,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(0.55f)
                        )
                    }
                }
                if (rateRange == null && rateBlock.rateParticipatePeople > 10 && onShowAllRatings != null) {
                    TextButton(
                        onClick = onShowAllRatings,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            text = i18n("查看全部評分"),
                            color = colors.brownPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

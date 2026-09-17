package xyz.desent.presentation.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.desent.presentation.theme.Spacing

@Composable
fun UserProfileSkeleton(
    modifier: Modifier = Modifier,
    minDisplayTimeMs: Long = 4000L
) {
    var showSkeleton by remember { mutableStateOf(true) }
    var startTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    
    LaunchedEffect(Unit) {
        startTime = System.currentTimeMillis()
        delay(minDisplayTimeMs)
        showSkeleton = false
    }
    
    if (showSkeleton) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShimmerEffect(
                    modifier = Modifier
                        .size(52.dp)
                        .padding(end = Spacing.md)
                        .clip(AvatarShape)
                )
                
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ShimmerEffect(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    
                    ShimmerEffect(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun FollowCountsSkeleton(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        repeat(2) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShimmerEffect(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                ShimmerEffect(
                    modifier = Modifier
                        .width(60.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            }
        }
    }
}

@Composable
fun rememberSkeletonState(
    isLoading: Boolean,
    minDisplayTimeMs: Long = 4000L
): SkeletonState {
    val state = remember { SkeletonState() }
    
    LaunchedEffect(isLoading) {
        if (isLoading) {
            state.startLoading(minDisplayTimeMs)
        } else {
            state.stopLoading()
        }
    }
    
    return state
}

class SkeletonState {
    private var showSkeleton by mutableStateOf(false)
    private var loadingStartTime by mutableLongStateOf(0L)
    
    fun startLoading(minDisplayTimeMs: Long) {
        if (!showSkeleton) {
            loadingStartTime = System.currentTimeMillis()
            showSkeleton = true
        }
    }
    
    fun stopLoading() {
        showSkeleton = false
    }
    
    fun shouldShowSkeleton(): Boolean = showSkeleton
}

@Composable
fun ShimmerEffect(
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnimation = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1000,
                easing = LinearEasing
            )
        ),
        label = "shimmer_slide"
    )
    
    val shimmerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val shimmerColors = listOf(
        shimmerColor,
        shimmerColor.copy(alpha = 0.3f),
        shimmerColor
    )
    
    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnimation.value, y = translateAnimation.value)
    )
    
    Box(
        modifier = modifier
            .background(brush = brush)
    )
}

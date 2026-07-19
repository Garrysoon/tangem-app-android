package com.tangem.feature.swap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tangem.core.ui.res.TangemTheme
import com.tangem.feature.swap.models.SwapTxPreview

@Composable
fun TransactionPreviewBlock(
    transactions: List<SwapTxPreview>,
    modifier: Modifier = Modifier,
) {
    if (transactions.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TangemTheme.dimens.spacing12),
        verticalArrangement = Arrangement.spacedBy(TangemTheme.dimens.spacing8),
    ) {
        Text(
            text = "Transaction Preview",
            style = TangemTheme.typography.subtitle1,
            color = TangemTheme.colors.text.primary1,
        )
        transactions.forEach { tx ->
            TransactionPreviewItem(tx)
        }
    }
}

@Composable
private fun TransactionPreviewItem(tx: SwapTxPreview) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = TangemTheme.colors.background.secondary,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Step ${tx.step}: ${tx.title}",
                style = TangemTheme.typography.subtitle2,
                color = TangemTheme.colors.text.primary1,
            )
            Text(
                text = "~${tx.estimatedGasUsd}",
                style = TangemTheme.typography.caption1,
                color = TangemTheme.colors.text.secondary,
            )
        }
        Text(
            text = tx.description,
            style = TangemTheme.typography.caption1,
            color = TangemTheme.colors.text.secondary,
        )
        if (tx.fromAmount.isNotEmpty() && tx.toAmount.isNotEmpty()) {
            Text(
                text = "${tx.fromAmount} -> ${tx.toAmount}",
                style = TangemTheme.typography.caption1,
                color = TangemTheme.colors.text.accent,
            )
        }
        if (tx.contractAddress != null) {
            Text(
                text = "${tx.contractName}: ${tx.contractAddress.take(10)}...${tx.contractAddress.takeLast(8)}",
                style = TangemTheme.typography.caption2,
                color = TangemTheme.colors.text.tertiary,
            )
        }
    }
}

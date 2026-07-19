package com.tangem.feature.swap.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import com.tangem.common.ui.footers.SendingText
import com.tangem.common.ui.notifications.NotificationUM
import com.tangem.core.ui.components.*
import com.tangem.core.ui.components.buttons.predefined.PredefinedPercentButtonsRow
import com.tangem.core.ui.components.notifications.Notification
import com.tangem.core.ui.extensions.resolveReference
import com.tangem.core.ui.extensions.stringReference
import com.tangem.core.ui.extensions.stringResourceSafe
import com.tangem.core.ui.res.TangemTheme
import com.tangem.core.ui.res.TangemThemePreview
import com.tangem.core.ui.test.SwapTokenScreenTestTags
import com.tangem.feature.swap.domain.models.domain.SwapUIMode
import com.tangem.feature.swap.domain.models.ui.PriceImpact
import com.tangem.feature.swap.models.*
import com.tangem.feature.swap.models.states.ProviderState
import com.tangem.feature.swap.models.states.SwapNotificationUM
import com.tangem.feature.swap.presentation.R
import com.tangem.feature.swap.ui.preview.SwapTransactionCardPreview.receiveCard
import com.tangem.feature.swap.ui.preview.SwapTransactionCardPreview.sendCard
import kotlinx.collections.immutable.persistentListOf

@Suppress("LongMethod")
@Composable
internal fun SwapScreenContent(
    state: SwapStateHolder,
    modifier: Modifier = Modifier,
    feeBlock: @Composable ((Modifier) -> Unit)? = null,
) {
    val keyboard by keyboardAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(color = TangemTheme.colors.background.secondary),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = TangemTheme.dimens.spacing16,
                    end = TangemTheme.dimens.spacing16,
                    top = TangemTheme.dimens.spacing8,
                    bottom = TangemTheme.dimens.spacing32,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TangemTheme.dimens.spacing16),
        ) {
            MainInfo(state)

            if (state.swapUIMode == SwapUIMode.Simple) {
                ProviderItemBlockSimple(state = state.providerState)
            } else {
                ProviderItemBlock(state = state.providerState)
            }

            // Slippage selector (shown for all swap modes)
            if (state.changeCardsButtonState == ChangeCardsButtonState.ENABLED) {
                SlippageSelector(
                    selectedPercent = state.slippagePercent,
                    options = state.slippageOptions,
                    onSelected = state.onSlippageChanged,
                )
            }

            if (state.swapUIMode == SwapUIMode.Limit) {
                LimitOrderPriceBlock(
                    targetPrice = state.limitOrderState?.targetPrice.orEmpty(),
                    onTargetPriceChanged = state.limitOrderState?.onTargetPriceChanged ?: {},
                    expiryHours = state.limitOrderState?.expiryHours ?: 24,
                    onExpiryChanged = state.limitOrderState?.onExpiryChanged ?: {},
                    fromTokenSymbol = state.limitOrderState?.fromTokenSymbol.orEmpty(),
                    toTokenSymbol = state.limitOrderState?.toTokenSymbol.orEmpty(),
                    selectedProtocol = state.limitOrderState?.selectedProtocol
                        ?: LimitOrderProtocol.ONEINCH_LOP_V4,
                    onProtocolChanged = state.limitOrderState?.onProtocolChanged ?: {},
                    isCreating = state.limitOrderState?.isCreating ?: false,
                    statusMessage = state.limitOrderState?.statusMessage,
                )
            }

            if (state.swapUIMode == SwapUIMode.Limit && state.limitOrderState?.activeOrders.orEmpty().isNotEmpty()) {
                ActiveLimitOrdersBlock(
                    orders = state.limitOrderState?.activeOrders.orEmpty(),
                    onCancelOrder = state.limitOrderState?.onCancelOrder ?: {},
                )
            }

            // Balance warning
            if (!state.balanceWarning.isNullOrEmpty()) {
                Text(
                    text = state.balanceWarning!!,
                    style = TangemTheme.typography.caption1,
                    color = TangemTheme.colors.text.accent,
                    modifier = Modifier.padding(horizontal = TangemTheme.dimens.spacing12),
                )
            }

            // Bridge monitoring progress
            if (state.isBridgeMonitoring) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TangemTheme.dimens.spacing12),
                    verticalArrangement = Arrangement.spacedBy(TangemTheme.dimens.spacing4),
                ) {
                    Text(
                        text = "Bridge Status",
                        style = TangemTheme.typography.subtitle2,
                        color = TangemTheme.colors.text.primary1,
                    )
                    if (state.bridgeFillPercent > 0f) {
                        LinearProgressIndicator(
                            progress = { state.bridgeFillPercent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                        )
                    }
                    Text(
                        text = state.bridgeFillStatusText,
                        style = TangemTheme.typography.caption1,
                        color = TangemTheme.colors.text.secondary,
                    )
                    if (state.onCancelBridge != null) {
                        PrimaryButton(
                            text = "Cancel Bridge",
                            enabled = true,
                            onClick = state.onCancelBridge!!,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Gas estimate summary
            if (state.totalGasEstimateUsd.isNotEmpty()) {
                Text(
                    text = "Est. gas: ${state.totalGasEstimateUsd}",
                    style = TangemTheme.typography.caption2,
                    color = TangemTheme.colors.text.tertiary,
                    modifier = Modifier.padding(horizontal = TangemTheme.dimens.spacing12),
                )
            }

            // Transaction preview (for cross-token cross-chain swaps)
            if (state.txPreview.isNotEmpty()) {
                TransactionPreviewBlock(
                    transactions = state.txPreview,
                )
            }

            feeBlock?.invoke(Modifier.fillMaxWidth())

            if (state.notifications.isNotEmpty()) SwapNotifications(notifications = state.notifications)

            SpacerHMax()

            if (state.tosState != null && state.providerState !is ProviderState.Empty) {
                ProviderTos(
                    tosState = state.tosState,
                    modifier = Modifier
                        .padding(top = TangemTheme.dimens.spacing16),
                )
            }
            if (state.transferFooter != null) {
                SendingText(
                    footerText = state.transferFooter,
                    modifier = Modifier.padding(
                        top = TangemTheme.dimens.spacing16,
                    ),
                )
            }

            MainButton(state = state)
        }

        if (keyboard is Keyboard.Opened) {
            when {
                state.predefinedButtons.isNotEmpty() -> {
                    PredefinedPercentButtonsRow(
                        items = state.predefinedButtons,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .imePadding(),
                    )
                }
                state.shouldShowMaxAmount -> {
                    Text(
                        text = stringResourceSafe(id = R.string.send_max_amount_label),
                        style = TangemTheme.typography.button,
                        color = TangemTheme.colors.text.primary1,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .imePadding()
                            .fillMaxWidth()
                            .background(TangemTheme.colors.button.secondary)
                            .clickable { state.onMaxAmountSelected?.invoke() }
                            .padding(
                                horizontal = TangemTheme.dimens.spacing14,
                                vertical = TangemTheme.dimens.spacing16,
                            ),
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}

@Composable
private fun MainInfo(state: SwapStateHolder) {
    ConstraintLayout(
        modifier = Modifier.fillMaxWidth(),
    ) {
        val (topCard, bottomCard, button) = createRefs()
        val priceImpact = state.priceImpact
        TransactionCard(
            priceImpact = priceImpact,
            swapCardState = state.sendCardData,
            modifier = Modifier.constrainAs(topCard) {
                top.linkTo(parent.top)
            },
            onSelectTokenClick = { state.onSelectTokenClick(TokenSelectionDirection.FROM) },
        )
        val marginCard = TangemTheme.dimens.spacing12
        if (state.swapUIMode == SwapUIMode.Simple) {
            TransactionCardSimple(
                priceImpact = priceImpact,
                swapCardState = state.receiveCardData,
                modifier = Modifier.constrainAs(bottomCard) {
                    top.linkTo(topCard.bottom, margin = marginCard)
                },
                onSelectTokenClick = { state.onSelectTokenClick(TokenSelectionDirection.TO) },
            )
        } else {
            TransactionCard(
                priceImpact = priceImpact,
                swapCardState = state.receiveCardData,
                modifier = Modifier.constrainAs(bottomCard) {
                    top.linkTo(topCard.bottom, margin = marginCard)
                },
                onSelectTokenClick = { state.onSelectTokenClick(TokenSelectionDirection.TO) },
            )
        }
        val marginButton = TangemTheme.dimens.spacing30
        SwapButton(
            state,
            modifier = Modifier.constrainAs(button) {
                bottom.linkTo(topCard.bottom, margin = -marginButton)
                start.linkTo(topCard.start)
                end.linkTo(topCard.end)
            },
        )
    }
}

@Composable
private fun ProviderTos(tosState: TosState, modifier: Modifier = Modifier) {
    val tos = tosState.tosLink
    val policy = tosState.policyLink
    if (tos == null && policy == null) return

    val (annotatedString, click) = getAnnotatedStringForLegalsWithClick(tos, policy)

    ClickableText(
        text = annotatedString,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TangemTheme.dimens.spacing54),
        style = TangemTheme.typography.caption2.copy(textAlign = TextAlign.Center),
        onClick = click,
    )
}

@Composable
private fun getAnnotatedStringForLegalsWithClick(
    tos: LegalState?,
    policy: LegalState?,
): Pair<AnnotatedString, (Int) -> Unit> {
    return if (tos != null && policy != null) {
        val tosTitle = tos.title.resolveReference()
        val policyTitle = policy.title.resolveReference()
        val fullString = stringResourceSafe(id = R.string.express_legal_two_placeholders, tosTitle, policyTitle)
        val tosIndex = fullString.indexOf(tosTitle)
        val policyIndex = fullString.indexOf(policyTitle)
        val string = buildAnnotatedString {
            withStyle(SpanStyle(color = TangemTheme.colors.text.tertiary)) {
                append(fullString.substring(0, tosIndex))
            }
            withStyle(SpanStyle(color = TangemTheme.colors.text.accent)) {
                append(fullString.substring(tosIndex, tosIndex + tosTitle.length))
            }
            withStyle(SpanStyle(color = TangemTheme.colors.text.tertiary)) {
                append(fullString.substring(tosIndex + tosTitle.length, policyIndex))
            }
            withStyle(SpanStyle(color = TangemTheme.colors.text.accent)) {
                append(fullString.substring(policyIndex, policyIndex + policyTitle.length))
            }
        }
        val click = { i: Int ->
            val tosStyle = requireNotNull(string.spanStyles.getOrNull(1))
            if (i in tosStyle.start..tosStyle.end) {
                tos.onClick(tos.link)
            }
            val policyStyle = requireNotNull(string.spanStyles.lastOrNull())
            if (i in policyStyle.start..policyStyle.end) {
                policy.onClick(policy.link)
            }
        }
        string to click
    } else {
        val legal = requireNotNull(tos ?: policy) { "tos or policy must not be null" }
        val legalTitle = legal.title
            .resolveReference()
        val fullString = stringResourceSafe(id = R.string.express_legal_one_placeholder, legal)
        val legalIndex = fullString.indexOf(legalTitle)
        val string = buildAnnotatedString {
            withStyle(SpanStyle(color = TangemTheme.colors.text.tertiary)) {
                append(fullString.substring(0, legalIndex))
            }
            withStyle(SpanStyle(color = TangemTheme.colors.text.accent)) {
                append(fullString.substring(legalIndex, legalIndex + legalTitle.length))
            }
        }
        val click = { i: Int ->
            val legalStyle = requireNotNull(string.spanStyles.lastOrNull())
            if (i in legalStyle.start..legalStyle.end) {
                legal.onClick(legal.link)
            }
        }
        string to click
    }
}

@Composable
private fun SwapButton(state: SwapStateHolder, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(TangemTheme.dimens.size48)
            .shadow(elevation = 2.dp, shape = CircleShape)
            .background(TangemTheme.colors.background.action)
            .clickable(
                enabled = state.changeCardsButtonState == ChangeCardsButtonState.ENABLED,
                onClick = state.onChangeCardsClicked,
                indication = ripple(),
                interactionSource = remember { MutableInteractionSource() },
            )
            .testTag(SwapTokenScreenTestTags.REPLACE_TOKENS_BUTTON),
    ) {
        when (state.changeCardsButtonState) {
            ChangeCardsButtonState.UPDATE_IN_PROGRESS -> {
                Box {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(TangemTheme.dimens.spacing12),
                        color = TangemTheme.colors.icon.primary1,
                        strokeWidth = TangemTheme.dimens.size2,
                    )
                }
            }
            ChangeCardsButtonState.ENABLED -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_exchange_vertical_24),
                    contentDescription = null,
                    tint = TangemTheme.colors.text.primary1,
                    modifier = Modifier.padding(TangemTheme.dimens.spacing12),
                )
            }
            ChangeCardsButtonState.DISABLED -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_exchange_vertical_24),
                    contentDescription = null,
                    tint = TangemTheme.colors.text.disabled,
                    modifier = Modifier.padding(TangemTheme.dimens.spacing12),
                )
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun SwapNotifications(notifications: List<NotificationUM>) {
    Column(
        modifier = Modifier
            .background(color = TangemTheme.colors.background.secondary)
            .fillMaxWidth(),
    ) {
        notifications.forEach { notification ->
            when (notification) {
                is SwapNotificationUM.Error.ApprovalInProgressWarning,
                is SwapNotificationUM.Error.TransactionInProgressWarning,
                -> {
                    CardWithIcon(
                        title = notification.config.title?.resolveReference().orEmpty(),
                        description = notification.config.subtitle.resolveReference(),
                        icon = {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(TangemTheme.dimens.size16),
                                color = TangemTheme.colors.icon.primary1,
                                strokeWidth = TangemTheme.dimens.size2,
                            )
                        },
                    )
                }
                else -> {
                    Notification(
                        config = notification.config,
                        iconTint = when (notification) {
                            is SwapNotificationUM.Warning.TradeTooHigh -> TangemTheme.colors.icon.warning
                            is SwapNotificationUM.Error.UnableToCoverFeeWarning,
                            is NotificationUM.Error.TokenExceedsBalance,
                            is NotificationUM.Error.ExceedsBalance,
                            is NotificationUM.Info,
                            is NotificationUM.Warning,
                            -> null
                            is SwapNotificationUM.Error.GenericError,
                            is NotificationUM.Error,
                            -> TangemTheme.colors.icon.warning
                        },
                    )
                }
            }
            SpacerH8()
        }
    }
}

@Composable
private fun MainButton(state: SwapStateHolder) {
    when {
        state.isInsufficientFunds -> {
            PrimaryButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResourceSafe(id = R.string.swapping_insufficient_funds),
                enabled = false,
                onClick = state.swapButton.onClick,
            )
        }

        state.swapButton.isHoldToConfirm -> {
            HoldToConfirmButton(
                modifier = Modifier.fillMaxWidth(),
                text = getButtonTitle(state.swapButton.mode),
                enabled = state.swapButton.isEnabled,
                onConfirm = state.swapButton.onClick,
                isLoading = state.swapButton.isInProgress,
            )
        }

        else -> {
            PrimaryButtonIconEnd(
                modifier = Modifier.fillMaxWidth(),
                text = getButtonTitle(state.swapButton.mode),
                iconResId = state.swapButton.walletInteractionIcon,
                enabled = state.swapButton.isEnabled,
                onClick = state.swapButton.onClick,
            )
        }
    }
}

@Composable
@ReadOnlyComposable
private fun getButtonTitle(mode: SwapButton.Mode): String {
    return when (mode) {
        SwapButton.Mode.SWAP_PROGRESSING -> stringResourceSafe(id = R.string.swapping_swap_action_in_progress)
        SwapButton.Mode.SWAP -> stringResourceSafe(id = R.string.swapping_swap_action)
        SwapButton.Mode.TRANSFER -> stringResourceSafe(id = R.string.swapping_transfer_action)
        SwapButton.Mode.TRANSFER_PROGRESSING -> stringResourceSafe(
            id = R.string.swapping_transfer_action_in_progress,
        )
        SwapButton.Mode.LIMIT_ORDER -> "Place Limit Order"
        SwapButton.Mode.LIMIT_ORDER_PROGRESSING -> "Placing Limit Order..."
    }
}

@Composable
private fun ActiveLimitOrdersBlock(
    orders: List<LimitOrderItem>,
    onCancelOrder: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TangemTheme.dimens.spacing12),
        verticalArrangement = Arrangement.spacedBy(TangemTheme.dimens.spacing8),
    ) {
        Text(
            text = "Active Orders (${orders.size})",
            style = TangemTheme.typography.subtitle1,
            color = TangemTheme.colors.text.primary1,
        )
        orders.forEach { order ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = TangemTheme.colors.background.secondary,
                        shape = TangemTheme.shapes.roundedCornersSmall,
                    )
                    .padding(TangemTheme.dimens.spacing12),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${order.fromSymbol} → ${order.toSymbol}",
                        style = TangemTheme.typography.subtitle2,
                        color = TangemTheme.colors.text.primary1,
                    )
                    Spacer(modifier = Modifier.height(TangemTheme.dimens.spacing4))
                    Text(
                        text = "Price: ${order.targetPrice} ${order.toSymbol}",
                        style = TangemTheme.typography.caption1,
                        color = TangemTheme.colors.text.secondary,
                    )
                    Text(
                        text = "Amount: ${order.amount} ${order.fromSymbol}",
                        style = TangemTheme.typography.caption1,
                        color = TangemTheme.colors.text.secondary,
                    )
                    Text(
                        text = "Expires: ${order.expiryLabel}",
                        style = TangemTheme.typography.caption1,
                        color = TangemTheme.colors.text.tertiary,
                    )
                }
                PrimaryButton(
                    modifier = Modifier.padding(start = TangemTheme.dimens.spacing8),
                    text = "Cancel",
                    enabled = true,
                    onClick = { onCancelOrder(order.id) },
                )
            }
        }
    }
}

@Composable
private fun SlippageSelector(
    selectedPercent: Float,
    options: List<Float>,
    onSelected: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TangemTheme.dimens.spacing12),
        horizontalArrangement = Arrangement.spacedBy(TangemTheme.dimens.spacing8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Slippage:",
            style = TangemTheme.typography.caption1,
            color = TangemTheme.colors.text.secondary,
        )
        options.forEach { pct ->
            val isSelected = pct == selectedPercent
            val bg = if (isSelected) TangemTheme.colors.background.tertiary else TangemTheme.colors.background.secondary
            val textColor = if (isSelected) TangemTheme.colors.text.primary1 else TangemTheme.colors.text.secondary
            Box(
                modifier = Modifier
                    .background(bg, shape = TangemTheme.shapes.roundedCornersSmall)
                    .clickable { onSelected(pct) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "${pct}%",
                    style = TangemTheme.typography.caption1,
                    color = textColor,
                )
            }
        }
    }
}

// region preview

private val state = SwapStateHolder(
    sendCardData = sendCard,
    receiveCardData = receiveCard,
    notifications = persistentListOf(
        SwapNotificationUM.Info.PermissionNeeded(
            onApproveClick = {},
            onLearnMoreClick = {},
        ),
        SwapNotificationUM.Warning.NoAvailableTokensToSwap("POLYGON"),
    ),
    swapButton = SwapButton(isEnabled = true, onClick = {}, walletInteractionIcon = null),
    onRefresh = {},
    onBackClicked = {},
    onChangeCardsClicked = {},
    permissionUM = SwapPermissionUM.Empty,
    providerState = ProviderState.Loading(),
    priceImpact = PriceImpact.Empty,
    shouldShowMaxAmount = true,
    isInsufficientFunds = false,
    onSuccess = {},
    onSelectTokenClick = { _ -> },
    tosState = TosState(
        tosLink = LegalState(
            title = stringReference("Terms of Use"),
            link = "https://tangem.com",
            onClick = {},
        ),
        policyLink = LegalState(
            title = stringReference("Privacy Policy"),
            link = "https://tangem.com",
            onClick = {},
        ),
    ),
    changeCardsButtonState = ChangeCardsButtonState.ENABLED,
)

@Composable
private fun LimitOrderPriceBlock(
    targetPrice: String,
    onTargetPriceChanged: (String) -> Unit,
    expiryHours: Int,
    onExpiryChanged: (Int) -> Unit,
    fromTokenSymbol: String,
    toTokenSymbol: String,
    selectedProtocol: LimitOrderProtocol,
    onProtocolChanged: (LimitOrderProtocol) -> Unit,
    isCreating: Boolean,
    statusMessage: String?,
) {
    var showExpiryDropdown by remember { mutableStateOf(false) }
    var showProtocolDropdown by remember { mutableStateOf(false) }

    val expiryOptions = listOf(
        1 to "1 hour",
        4 to "4 hours",
        24 to "1 day",
        168 to "7 days",
        720 to "30 days",
        -1 to "Until cancelled",
    )

    val currentExpiryLabel = expiryOptions.find { it.first == expiryHours }?.second ?: "${expiryHours}h"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TangemTheme.dimens.spacing12),
        verticalArrangement = Arrangement.spacedBy(TangemTheme.dimens.spacing12),
    ) {
        Text(
            text = "Limit Order",
            style = TangemTheme.typography.subtitle1,
            color = TangemTheme.colors.text.primary1,
        )

        // Protocol selector
        Box {
            OutlinedTextField(
                value = selectedProtocol.displayName,
                onValueChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showProtocolDropdown = true },
                readOnly = true,
                enabled = false,
                label = { Text("Protocol") },
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { showProtocolDropdown = true },
            )
            DropdownMenu(
                expanded = showProtocolDropdown,
                onDismissRequest = { showProtocolDropdown = false },
            ) {
                LimitOrderProtocol.entries.forEach { protocol ->
                    DropdownMenuItem(
                        text = { Text(protocol.displayName) },
                        onClick = {
                            onProtocolChanged(protocol)
                            showProtocolDropdown = false
                        },
                    )
                }
            }
        }

        // Target price input
        OutlinedTextField(
            value = targetPrice,
            onValueChange = { new ->
                if (new.isEmpty() || new.matches(Regex("^\\d*\\.?\\d*\$"))) {
                    onTargetPriceChanged(new)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = if (fromTokenSymbol.isNotEmpty() && toTokenSymbol.isNotEmpty()) {
                        "e.g. 1 $fromTokenSymbol = ??? $toTokenSymbol"
                    } else {
                        "e.g. 1800.00"
                    },
                )
            },
            label = { Text("Target price ($toTokenSymbol per 1 $fromTokenSymbol)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )

        // Expiry selector
        Box {
            OutlinedTextField(
                value = currentExpiryLabel,
                onValueChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showExpiryDropdown = true },
                readOnly = true,
                enabled = false,
                label = { Text("Expires in") },
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { showExpiryDropdown = true },
            )
            DropdownMenu(
                expanded = showExpiryDropdown,
                onDismissRequest = { showExpiryDropdown = false },
            ) {
                expiryOptions.forEach { (hours, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onExpiryChanged(hours)
                            showExpiryDropdown = false
                        },
                    )
                }
            }
        }

        // Status message
        if (statusMessage != null) {
            Text(
                text = statusMessage,
                style = TangemTheme.typography.caption1,
                color = if (statusMessage.startsWith("Error") || statusMessage.startsWith("Failed")) {
                    TangemTheme.colors.text.accent
                } else {
                    TangemTheme.colors.text.accent
                },
            )
        }
    }
}

@Preview(widthDp = 360, showBackground = true)
@Preview(widthDp = 360, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SwapScreenContentPreview() {
    TangemThemePreview {
        SwapScreenContent(state = state, modifier = Modifier)
    }
}

// endregion preview
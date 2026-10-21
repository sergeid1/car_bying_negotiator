package com.dealanalyzer.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Versioned presentation facts beside the original English prose. Never edits evidence or old payloads. */
public final class FindingText {
  public record Finding(String code, Map<String, Object> parameters, String text) {}
  private record Template(String code, String en, String es, Pattern pattern, java.util.List<String> names) {}
  private static final Map<String, Template> TEMPLATES = new LinkedHashMap<>();
  static {
    loadAddOns();
    loadGovernmentFees();
    add("REVIEW_MATCH", "Verified terms and cash calculation match the approved version within both approved ceilings.", "Los términos verificados y el cálculo de la compra al contado coinciden con la versión aprobada dentro de ambos límites aprobados.");
    add("REVIEW_UNVERIFIABLE", "Missing, stale, conflicting, or unclassified evidence prevents reliable verification.", "La información faltante, desactualizada, contradictoria o sin clasificar impide una verificación fiable.");
    add("REVIEW_NEEDED", "Approval, term differences, or approved price limits need Buyer review.", "La aprobación, las diferencias en los términos o los límites de precio aprobados requieren su revisión.");
    add("OTD_RECONCILES", "The quoted OTD reconciles under the recorded jurisdiction policy.", "El precio total final (out-the-door) cotizado concuerda con el cálculo según las reglas de la jurisdicción registradas.");
    add("OTD_DIFFERS", "The quoted OTD is missing or differs from the recorded cash calculation.", "El precio total final (out-the-door) cotizado falta o difiere del cálculo registrado de la compra al contado.");
    add("MARKET_SEPARATE", "The market rating is a separate estimated selling-price comparison.", "La evaluación del mercado es una comparación estimada y separada del precio de venta.");
    add("MARKET_UNAVAILABLE", "Market analysis is unavailable; available cash economics remain visible.", "El análisis de mercado no está disponible; los cálculos disponibles de la compra al contado siguen visibles.");
    add("FINANCE_STATUS", "Dealer financing: {status}.", "Financiamiento del concesionario: {status}.");
    add("PAYMENT_DIFFERS", "Quoted payment differs beyond the recorded tolerance; ask the dealer to reconcile.", "El pago cotizado difiere más allá de la tolerancia registrada; pida al concesionario que explique la diferencia.");
    add("FINANCE_CALCULATED", "Financing uses independently calculated payments and Buyer-confirmed inputs.", "El financiamiento usa pagos calculados de forma independiente y datos confirmados por usted.");
    add("FINANCE_RESOLVE", "Resolve the missing, unsupported or stale financing inputs before relying on loan economics.", "Resuelva los datos de financiamiento faltantes, no admitidos o desactualizados antes de basarse en los cálculos del préstamo.");
    add("FINANCE_NO_BASELINE", "Financing is not checked against an approved financing baseline; review the full-term cost.", "El financiamiento no se compara con una versión de referencia aprobada; revise el costo durante todo el plazo.");
    add("NEXT_CONFIRM", "Confirm the dealer is still offering this exact version before proceeding.", "Confirme que el concesionario sigue ofreciendo esta misma versión antes de continuar.");
    add("NEXT_RESOLVE", "Resolve missing evidence, changed terms, or price-limit questions with the dealer before deciding.", "Aclare con el concesionario la información faltante, los términos modificados o las dudas sobre los límites de precio antes de decidir.");
    add("REVIEW_LABEL_MATCH", "Matches approved deal", "Coincide con la oferta aprobada");
    add("REVIEW_LABEL_UNKNOWN", "Unable to verify", "No se puede verificar");
    add("REVIEW_LABEL_NEEDED", "Review required", "Requiere revisión");
    add("RATING_BASIS", "Estimated selling-price comparison; OTD market rating is not available.", "Comparación estimada del precio de venta; no hay una evaluación de mercado del precio total final (out-the-door).");
    add("SCOPE_CASH", "Checks cover the frozen verified offer, approved limits, identity evidence and recorded cash calculation as of {asOf}.", "Las comprobaciones abarcan la oferta verificada guardada, los límites aprobados, la identificación y el cálculo registrado de la compra al contado al {asOf}.");
    add("SCOPE_FINANCE", "Financing checks cover Buyer-confirmed inputs and the declared schedule as of {asOf}; approved financing limits and independent purchase-charge reconciliation are not covered.", "Las comprobaciones de financiamiento abarcan los datos confirmados por usted y el calendario declarado al {asOf}; no abarcan límites de financiamiento aprobados ni una conciliación independiente de los cargos de compra.");
    add("SCOPE_ESTIMATES", "Tax and fee rules are recorded estimates; unresolved classifications prevent a matching verdict.", "Los cálculos de impuestos y cargos son estimaciones registradas; las clasificaciones pendientes impiden indicar una coincidencia.");
    add("SCOPE_EXCLUSIONS", "Financing covers only the recorded standard monthly schedule. Mechanical condition, title, legal compliance and purchase safety are excluded. Savings are not guaranteed.", "El financiamiento abarca solo el calendario mensual estándar registrado. No se evalúan el estado mecánico, el título, el cumplimiento legal ni la seguridad de la compra. No podemos prometer ahorros.");
    add("AMOUNT_OTD", "Out-the-door total", "Precio total final (out-the-door)");
    add("AMOUNT_PURCHASE", "Purchase price", "Precio de compra");
    add("AMOUNT_FINANCED", "Amount financed", "Monto financiado (amount financed)");
    add("AMOUNT_DUE", "Net amount due", "Monto neto por pagar");
    add("AMOUNT_LENDER_FINANCED", "Financed lender fees", "Cargos del prestamista financiados");
    add("AMOUNT_LENDER_UPFRONT", "Upfront lender fees (included in cash paid)", "Cargos iniciales del prestamista (incluidos en el efectivo pagado)");
    add("AMOUNT_CLOSING", "Cash paid at or before closing", "Efectivo pagado al cierre o antes");
    add("AMOUNT_DEFERRED", "Known deferred charges", "Cargos diferidos conocidos");
    add("AMOUNT_MONTHLY", "Calculated monthly payment", "Pago mensual calculado");
    add("AMOUNT_FINAL", "Final payment", "Último pago");
    add("AMOUNT_SCHEDULED", "Total scheduled payments", "Total de pagos programados");
    add("AMOUNT_INTEREST", "Total interest", "Intereses totales");
    add("AMOUNT_TRANSACTION", "Total Transaction Cost", "Costo total de la transacción");
    add("AMOUNT_MARKET_LOW", "Market selling-price low", "Límite inferior del precio de venta de mercado");
    add("AMOUNT_MARKET_HIGH", "Market selling-price high", "Límite superior del precio de venta de mercado");
    add("AMOUNT_MARKET_UNKNOWN", "Market selling-price range", "Rango de precios de venta de mercado");
    add("AMOUNT_DOCUMENT", "{label}", "{label}");
    add("TACTIC_PAYMENT_LABEL", "Possible payment packing", "Posibles productos incluidos en el pago (payment packing)");
    add("TACTIC_TERM_LABEL", "Stretched term", "Plazo extendido");
    add("TACTIC_APR_LABEL", "APR above your own offer", "Tasa porcentual anual (APR) superior a su propia oferta");
    add("TACTIC_DEALER_LESS_LABEL", "Dealer financing costs less", "El financiamiento del concesionario cuesta menos");
    add("TACTIC_EQUITY_LABEL", "Negative equity folded in", "Saldo negativo incorporado");
    add("TACTIC_ALLOWANCE_LABEL", "Allowance paid for by the price", "Valor en parte de pago compensado por el precio");
    add("TACTIC_PAYMENT", "The quoted payment is {difference} higher per month than the worksheet's own numbers produce. It may include products not shown; ask for a reconciliation.", "El pago cotizado es {difference} mayor por mes que el resultado de los propios números de la hoja. Puede incluir productos que no aparecen; pida que expliquen la diferencia.");
    add("TACTIC_TERM_COST", "The worksheet's {dealerMonths} month term is longer than your stated {buyerMonths} months. The longer term lowers the monthly payment by {paymentDifference} and adds {interestDifference} in scheduled interest.", "El plazo de {dealerMonths} meses de la hoja es mayor que los {buyerMonths} meses que usted indicó. El plazo mayor reduce el pago mensual en {paymentDifference} y agrega {interestDifference} en intereses programados.");
    add("TACTIC_TERM", "The worksheet's {dealerMonths} month term is longer than your stated {buyerMonths} months.", "El plazo de {dealerMonths} meses de la hoja es mayor que los {buyerMonths} meses que usted indicó.");
    add("TACTIC_APR_COST", "The dealer APR of {dealerApr}% is above your own offer's {buyerApr}%. At the same amount financed and term, this adds {interestDifference} in scheduled interest.", "La tasa de porcentaje anual (APR) del concesionario de {dealerApr}% supera el {buyerApr}% de su propia oferta. Con el mismo monto financiado y plazo, agrega {interestDifference} en intereses programados.");
    add("TACTIC_APR", "The dealer APR of {dealerApr}% is above your own offer's {buyerApr}%.", "La tasa de porcentaje anual (APR) del concesionario de {dealerApr}% supera el {buyerApr}% de su propia oferta.");
    add("TACTIC_DEALER_LESS_COST", "The dealer's financing costs less than your own offer: its APR of {dealerApr}% is below your own offer's {buyerApr}%. At the same amount financed and term, it is {interestDifference} less in scheduled interest.", "El financiamiento del concesionario cuesta menos que su propia oferta: su tasa de porcentaje anual (APR) de {dealerApr}% es menor que el {buyerApr}% de su propia oferta. Con el mismo monto financiado y plazo, cuesta {interestDifference} menos en intereses programados.");
    add("TACTIC_DEALER_LESS", "The dealer's financing costs less than your own offer: its APR of {dealerApr}% is below your own offer's {buyerApr}%.", "El financiamiento del concesionario cuesta menos que su propia oferta: su tasa de porcentaje anual (APR) de {dealerApr}% es menor que el {buyerApr}% de su propia oferta.");
    add("TACTIC_PAYMENT_QUESTION", "Please show how this payment follows from the price, amount financed, APR, term and down payment, including any products in it.", "Por favor muestre cómo se calcula este pago a partir del precio, monto financiado, tasa de porcentaje anual (APR), plazo y pago inicial, incluidos los productos que contiene.");
    add("TACTIC_TERM_QUESTION", "Please show the payment and total interest at my shorter term, with the same amount financed and APR.", "Por favor muestre el pago y los intereses totales con mi plazo menor, usando el mismo monto financiado y tasa de porcentaje anual (APR).");
    add("TACTIC_APR_QUESTION", "Please show the payment and total interest using my own lender's APR, with the same amount financed and term.", "Por favor muestre el pago y los intereses totales con la tasa de porcentaje anual (APR) de mi prestamista, usando el mismo monto financiado y plazo.");
    add("TACTIC_DEALER_QUESTION", "Please confirm the contract will show this APR, term and amount financed, with no products added to the loan.", "Por favor confirme que el contrato mostrará esta tasa de porcentaje anual (APR), plazo y monto financiado, sin productos agregados al préstamo.");
    add("TACTIC_EQUITY_QUESTION", "Please show where the amount still owed on my trade is included in the loan, and its payment and interest effect.", "Por favor muestre dónde se incluye en el préstamo lo que todavía debo por mi vehículo en parte de pago y su efecto en el pago y los intereses.");
    add("TACTIC_ALLOWANCE_QUESTION", "Please show the trade allowance with the selling price and discount we agreed, so I can compare their combined effect.", "Por favor muestre el valor en parte de pago junto con el precio de venta y el descuento acordados para comparar su efecto conjunto.");
    add("TACTIC_MISSING_APR", "Amount financed or APR is missing or outside the supported ordinary monthly schedule.", "El monto financiado o la tasa de porcentaje anual (APR) falta o queda fuera del calendario mensual estándar admitido.");
    add("TACTIC_MISSING_TERM", "Amount financed or term is missing or outside the supported ordinary monthly schedule.", "El monto financiado o el plazo falta o queda fuera del calendario mensual estándar admitido.");
    add("LISTING_VIN", "The listing you saved is for VIN {listingVin}, but this paperwork is for VIN {paperworkVin}, so their prices are not compared.", "El anuncio guardado corresponde al número de identificación del vehículo (VIN) {listingVin}, pero estos documentos corresponden al VIN {paperworkVin}; sus precios no se comparan.");
    add("LISTING_VIN_QUESTION", "Is this paperwork for the car in the listing, VIN {listingVin}?", "¿Estos documentos corresponden al vehículo del anuncio, VIN {listingVin}?");
    add("LISTING_NO_PRICE", "This paperwork shows no vehicle price to set beside the {advertised} in the listing you saved on {date}.", "Estos documentos no muestran un precio del vehículo para comparar con los {advertised} del anuncio que guardó el {date}.");
    add("LISTING_PRICE_QUESTION", "The listing on {date} showed {advertised}. What is the vehicle price on this paperwork?", "El anuncio del {date} mostraba {advertised}. ¿Cuál es el precio del vehículo en estos documentos?");
    add("LISTING_HIGHER", "The listing you saved on {date} showed {advertised}. The vehicle price on this paperwork is {price}, {difference} more.", "El anuncio que guardó el {date} mostraba {advertised}. El precio del vehículo en estos documentos es {price}, es decir, {difference} más.");
    add("LISTING_HIGHER_QUESTION", "The listing on {date} showed {advertised}. Why is the price on this paperwork {price}?", "El anuncio del {date} mostraba {advertised}. ¿Por qué el precio en estos documentos es {price}?");
    add("LISTING_SAME", "The vehicle price on this paperwork, {price}, is the same as the listing you saved on {date}.", "El precio del vehículo en estos documentos, {price}, coincide con el del anuncio que guardó el {date}.");
    add("LISTING_LOWER", "The vehicle price on this paperwork, {price}, is {difference} less than the {advertised} in the listing you saved on {date}.", "El precio del vehículo en estos documentos, {price}, es {difference} menor que los {advertised} del anuncio que guardó el {date}.");
    add("ADDON_LOAN_CASH", "It covers a loan, and this is recorded as a cash purchase. Ask what it would cover.", "Cubre un préstamo, pero esta compra está registrada como una compra al contado. Pregunte qué cubriría.");
    add("ADDON_CASH", "This is recorded as a cash purchase, so its price is paid now, with no interest on it.", "Esta compra está registrada como una compra al contado, por lo que su precio se paga ahora, sin intereses.");
    add("ADDON_FINANCED", "Financed with the car, it also adds interest for the whole loan term, on top of its price.", "Al financiarse junto con el vehículo, además de su precio agrega intereses durante todo el plazo del préstamo.");
    add("ADDON_MAY_FINANCE", "If it is financed with the car, it also adds interest for the whole loan term.", "Si se financia junto con el vehículo, también agrega intereses durante todo el plazo del préstamo.");
    add("ADDON_UNKNOWN", "Unrecognised charge — ask what it is.", "Cargo no reconocido — pregunte de qué se trata.");
    add("ADDON_KIND_OPTIONAL", "Usually optional and negotiable.", "Suele ser opcional y negociable.");
    add("ADDON_KIND_PRICE", "A price increase on the car, not a service. It is negotiable.", "Un aumento del precio del vehículo, no un servicio. Es negociable.");
    add("ADDON_KIND_DUPLICATE", "May repeat a charge already on the paperwork.", "Puede repetir un cargo que ya figura en los documentos.");
    add("ADDON_KIND_FINANCE", "Optional product; you can decline it or buy it elsewhere.", "Producto opcional; puede rechazarlo o comprarlo en otro lugar.");
    add("ADDON_KIND_MANUFACTURER", "Usually set by the manufacturer and printed on the window sticker.", "Suele fijarlo el fabricante y aparecer en la etiqueta de ventanilla.");
    add("ADDON_SUMMARY", "Named charges: {recognized}; possible add-ons: {possible}; unrecognised charges: {unknown}. Usually optional items total {optionalTotal}; possible add-ons total {possibleTotal}; unrecognised charges total {unknownTotal}. Unread amounts excluded from totals: {unread}.", "Cargos identificados: {recognized}; posibles productos adicionales: {possible}; cargos no reconocidos: {unknown}. Los artículos habitualmente opcionales suman {optionalTotal}; los posibles productos adicionales suman {possibleTotal}; los cargos no reconocidos suman {unknownTotal}. Montos no leídos excluidos de los totales: {unread}.");
    add("ADDON_NAMES", "{names}", "{names}");
    add("ADDON_WHATS", "{descriptions}", "{descriptions}");
    add("ADDON_POSSIBLE_QUESTION", "Is “{label}” {names}? What does it cover, and is it optional?", "¿Es «{label}» {names}? ¿Qué cubre y es opcional?");
    add("ADDON_COMBINED_QUESTION", "“{label}” looks like {names}. What does it include, and will you take any of it off the price?", "«{label}» parece ser {names}. ¿Qué incluye y quitará alguna parte del precio?");
    add("UNKNOWN", "Unknown", "Desconocido");
    add("TRADE_TRADE_NOT_RECORDED", "The paperwork shows a trade allowance of {allowance}, but no trade is recorded. Record it before comparing its value and written offers.", "Los documentos muestran un valor de {allowance} por el vehículo en parte de pago (trade-in), pero no hay uno registrado. Regístrelo antes de comparar su valor y las ofertas escritas.");
    add("TRADE_TRADE_VIN_DIFFERENT", "The paperwork trade VIN {paperworkVin} differs from your recorded VIN {recordedVin}. No trade comparison is made until this is corrected.", "El VIN {paperworkVin} del vehículo en parte de pago difiere del VIN registrado {recordedVin}. No se compara el vehículo hasta corregirlo.");
    add("TRADE_TRADE_VIN_SAME", "The trade VIN matches the recorded trade.", "El VIN del vehículo en parte de pago coincide con el vehículo registrado.");
    add("TRADE_TRADE_VIN_NOT_SHOWN", "The paperwork does not show the trade VIN, so it cannot be compared. Ask to see it on the buyer's order.", "Los documentos no muestran el VIN del vehículo en parte de pago; no se puede comparar. Pida que aparezca en la orden de compra (buyer's order).");
    add("TRADE_BELOW_WRITTEN_OFFER", "The allowance {allowance} is below your written offer of {writtenOffer} by {difference}. The tax credit is {taxCredit}; the remaining gap after tax is {gapAfterTax}.", "El valor de {allowance} por el vehículo en parte de pago es {difference} menor que su oferta escrita de {writtenOffer}. La reducción de impuestos es {taxCredit}; la diferencia restante después de impuestos es {gapAfterTax}.");
    add("TRADE_AT_OR_ABOVE_WRITTEN_OFFER", "The allowance {allowance} is at or above your written offer of {writtenOffer}.", "El valor de {allowance} por el vehículo en parte de pago es igual o mayor que su oferta escrita de {writtenOffer}.");
    add("TRADE_BELOW_VALUATION_RANGE", "The allowance {allowance} is below the estimated range {low} to {high} from {source}. Estimate checked: {checked}. Older estimate: {old}.", "El valor de {allowance} por el vehículo en parte de pago está por debajo del rango estimado de {low} a {high} de {source}. Fecha de consulta de la estimación: {checked}. Estimación antigua: {old}.");
    add("TRADE_WITHIN_VALUATION_RANGE", "The allowance {allowance} is within the estimated range {low} to {high} from {source}. Estimate checked: {checked}. Older estimate: {old}.", "El valor de {allowance} por el vehículo en parte de pago está dentro del rango estimado de {low} a {high} de {source}. Fecha de consulta de la estimación: {checked}. Estimación antigua: {old}.");
    add("TRADE_ABOVE_VALUATION_RANGE", "The allowance {allowance} is above the estimated range {low} to {high} from {source}. Estimate checked: {checked}. Older estimate: {old}.", "El valor de {allowance} por el vehículo en parte de pago está por encima del rango estimado de {low} a {high} de {source}. Fecha de consulta de la estimación: {checked}. Estimación antigua: {old}.");
    add("TRADE_VALUATION_UNAVAILABLE", "A trade valuation is unavailable; the allowance is not compared with a value range.", "No hay una valoración disponible del vehículo en parte de pago; el valor ofrecido no se compara con un rango de valores.");
    add("TRADE_PAYOFF_NOT_OWED", "The paperwork uses a payoff of {paperworkPayoff}, but you recorded that nothing is owed on your trade.", "Los documentos usan un saldo del préstamo (payoff) de {paperworkPayoff}, pero usted indicó que no debe nada por su vehículo en parte de pago.");
    add("TRADE_PAYOFF_NOT_SHOWN", "The paperwork shows no payoff; your recorded payoff is {buyerPayoff}, valid through {goodThrough}.", "Los documentos no muestran el saldo del préstamo; su saldo registrado es {buyerPayoff}, válido hasta el {goodThrough}.");
    add("TRADE_PAYOFF_DIFFERS", "The paperwork payoff {paperworkPayoff} differs from your payoff {buyerPayoff}, valid through {goodThrough}.", "El saldo del préstamo de {paperworkPayoff} en los documentos difiere de su saldo de {buyerPayoff}, válido hasta el {goodThrough}.");
    add("TRADE_PAYOFF_SAME", "The paperwork payoff {paperworkPayoff} matches your payoff valid through {goodThrough}.", "El saldo del préstamo de {paperworkPayoff} en los documentos coincide con su saldo válido hasta el {goodThrough}.");
    add("TRADE_PAYOFF_STALE", "Your payoff {buyerPayoff}, valid through {goodThrough}, is stale and cannot be used for this check. Get a current lender payoff.", "Su saldo del préstamo de {buyerPayoff}, válido hasta el {goodThrough}, está desactualizado y no sirve para esta comprobación. Solicite un saldo vigente a su prestamista.");
    add("TRADE_PAYOFF_UNKNOWN", "Your payoff is unknown, so what is owed on your trade cannot be checked. Get a payoff quote from your lender.", "Su saldo del préstamo es desconocido, por lo que no se puede comprobar cuánto debe por su vehículo en parte de pago. Solicite el saldo a su prestamista.");
    add("TRADE_NEGATIVE_EQUITY_FINANCED", "You owe {negative} more than the allowance; this negative equity is added to the amount financed. Scheduled payment increase: {paymentIncrease}; scheduled total increase: {scheduledIncrease}; interest: {interest}. Unknown figures need current financing inputs.", "Usted debe {negative} más que el valor ofrecido; este saldo negativo (negative equity) se agrega al monto financiado. Aumento del pago programado: {paymentIncrease}; aumento total programado: {scheduledIncrease}; intereses: {interest}. Las cifras desconocidas requieren datos de financiamiento vigentes.");
    add("TRADE_NEGATIVE_EQUITY", "You owe {negative} more than the allowance; that negative equity increases what you pay for this car by {negative}.", "Usted debe {negative} más que el valor ofrecido; ese saldo negativo aumenta en {negative} lo que paga por este vehículo.");
    add("TRADE_EQUITY", "Trade equity is {equity}: the allowance {allowance} less the recorded payoff.", "El valor neto del vehículo en parte de pago es {equity}: el valor ofrecido de {allowance} menos el saldo del préstamo registrado.");
    add("TRADE_ALLOWANCE_OFFSET_BY_PRICE", "The allowance is {aboveValue} above the reference value, but the price is {priceIncrease} higher than agreed. Combined effect: {netEffect}; positive means ahead and negative means behind. The reference may be an estimate.", "El valor ofrecido supera el valor de referencia en {aboveValue}, pero el precio es {priceIncrease} mayor que lo acordado. Efecto conjunto: {netEffect}; positivo indica una ventaja y negativo una desventaja. La referencia puede ser una estimación.");
    add("TRADE_ALLOWANCE_ABOVE_VALUE", "The allowance exceeds the reference value by {aboveValue}. Compare it with the selling price, where the higher allowance may be paid for. The reference may be an estimate.", "El valor ofrecido supera el valor de referencia en {aboveValue}. Compárelo con el precio de venta, donde puede compensarse un valor en parte de pago mayor. La referencia puede ser una estimación.");
    add("TRADE_QUESTION", "Please explain the trade VIN, allowance and lender payoff in writing, and show their effect beside the agreed selling price.", "Por favor explique por escrito el VIN, el valor ofrecido y el saldo del préstamo del vehículo en parte de pago, y muestre su efecto junto al precio de venta acordado.");
    add("TRADE_COST_UNAVAILABLE", "Its scheduled payment and interest effect cannot be calculated from the recorded amount financed, APR and term.", "No se puede calcular su efecto en los pagos e intereses con el monto financiado, la tasa de porcentaje anual (APR) y el plazo registrados.");
    add("TRADE_TAX_CALCULATION", "Trade-related tax reduction under {ruleVersion} for {jurisdiction}: {taxCredit}. This uses the recorded taxable price and trade allowance; selling the car elsewhere does not give this credit.", "Reducción de impuestos por el vehículo en parte de pago según {ruleVersion} para {jurisdiction}: {taxCredit}. Se usan el precio gravable y el valor en parte de pago registrados; vender el vehículo en otro lugar no da esta reducción.");
    add("TRADE_TAX_UNKNOWN", "The trade-related tax reduction is unavailable because the applicable rules or price inputs are missing.", "La reducción de impuestos por el vehículo en parte de pago no está disponible porque faltan las reglas aplicables o los datos del precio.");
    add("YES", "Yes", "Sí");
    add("NO", "No", "No");
    add("DESK_ADDED_CHARGE_READ", "Added: {label}, {amount}. It is not in your Agreed Price.", "Se agregó «{label}», de {amount}. No figuraba en su precio acordado.");
    add("TACTIC_EQUITY_BASIS_COST", "From {ruleVersion}; equity basis {equityBasis}; scheduled-payment effect includes principal and interest under {calculationVersion}", "Según {ruleVersion}; base del valor neto: {equityBasis}; el efecto en los pagos programados incluye el monto principal y los intereses según {calculationVersion}");
    add("TACTIC_EQUITY_BASIS_PRINCIPAL", "From {ruleVersion}; equity basis {equityBasis}; principal amount only; scheduled payments and interest unavailable", "Según {ruleVersion}; base del valor neto: {equityBasis}; solo el monto principal; los pagos programados y los intereses no están disponibles");
    add("TACTIC_TOGETHER_BASIS", "Net trade-and-price effect from {ruleVersion}; reference {reference}; positive means the Buyer is ahead, negative means behind. This purchase-price comparison has no monthly effect.", "Efecto neto del vehículo en parte de pago y el precio según {ruleVersion}; referencia: {reference}; positivo indica una ventaja para usted y negativo una desventaja. Esta comparación del precio de compra no tiene efecto mensual.");
    add("TACTIC_EQUITY_EFFECT_UNKNOWN", "The scheduled payment and interest effect is unavailable; the amount of negative equity is shown instead.", "El efecto en los pagos e intereses no está disponible; en su lugar se muestra el monto del saldo negativo.");
    add("TACTIC_TOGETHER_UNKNOWN", "The combined trade-and-price dollar effect is unavailable in the trade check.", "El efecto monetario conjunto del vehículo en parte de pago y el precio no está disponible en la comprobación.");
    add("TRADE_COST_MISSING_INPUT", "Its cost over the loan needs the amount financed, APR and term from the paperwork, and one of them is missing.", "Para calcular su costo durante el préstamo se necesitan el monto financiado, la tasa de porcentaje anual (APR) y el plazo de los documentos, y falta uno de ellos.");
    add("TRADE_COST_UNCALCULABLE", "Its cost over the loan could not be calculated from the paperwork's amount financed, APR and term.", "No se pudo calcular su costo durante el préstamo con el monto financiado, la tasa de porcentaje anual (APR) y el plazo de los documentos.");
    add("TRADE_BUYER_VALUE_OLD", "You looked this value up on {source} and entered it yourself; otdog has not checked it, and it is not an offer. It is old, from {date}.", "Usted consultó este valor en {source} y lo ingresó; otdog no lo ha comprobado y no es una oferta. Está desactualizado, con fecha del {date}.");
    add("TRADE_BUYER_VALUE", "You looked this value up on {source} and entered it yourself; otdog has not checked it, and it is not an offer.", "Usted consultó este valor en {source} y lo ingresó; otdog no lo ha comprobado y no es una oferta.");
    add("TRADE_MISSING_ZIP", "A trade-in value range needs the dealer's five-digit ZIP, which this case does not have.", "Para obtener un rango de valores del vehículo en parte de pago se necesita el código postal de cinco dígitos del concesionario, que este caso no tiene.");
    add("EQUITY_PAPERWORK", "PAPERWORK_PAYOFF", "saldo del préstamo de los documentos");
    add("EQUITY_NOTHING_OWED", "NOTHING_OWED", "no hay saldo pendiente");
    add("EQUITY_BUYER", "BUYER_PAYOFF", "saldo del préstamo ingresado por usted");
    add("REFERENCE_OFFER", "WRITTEN_OFFER", "oferta escrita");
    add("REFERENCE_RANGE", "VALUATION_HIGH", "límite superior del rango estimado");
    add("REFERENCE_BUYER_RANGE", "BUYER_ESTIMATE_HIGH", "límite superior de la estimación ingresada por usted");
    add("OPERAND_APR", "Annual percentage rate", "Tasa de porcentaje anual (APR)");
    add("OPERAND_MONTHS", "Months", "Meses");
    add("OPERAND_REGULAR_PAYMENTS", "Regular payments", "Pagos regulares");
    add("OPERAND_PAYMENTS_OF", "Regular monthly payments of {amount}", "Pagos mensuales regulares de {amount}");
    add("OPERAND_HALF_SPREAD", "Half of (maximum − opening), rounded down to the cent", "La mitad de (máximo − precio inicial), redondeada hacia abajo al centavo");
    add("OPERAND_OPENING_SELLING", "Opening selling price", "Precio de venta inicial");
    add("OPERAND_OPENING_OTD", "Opening out-the-door", "Precio total final inicial");
    add("OPERAND_MAXIMUM_SELLING", "Policy maximum selling price", "Precio de venta máximo según la política");
    add("OPERAND_MAXIMUM_OTD", "Policy maximum out-the-door", "Precio total final máximo según la política");
    add("OPERAND_YOUR_SELLING_LIMIT", "Your selling price limit", "Su límite del precio de venta");
    add("OPERAND_YOUR_OTD_LIMIT", "Your out-the-door limit", "Su límite del precio total final");
    add("OPERAND_RECORDED_SELLING", "Selling price in the Dealer Offer", "Precio de venta de la oferta del concesionario");
    add("OPERAND_TARGET_SELLING", "Target selling price", "Precio de venta objetivo");
    add("OPERAND_RECORDED_OTD", "Recorded out-the-door total", "Precio total final registrado");
    add("OPERAND_SELLING_CHANGE", "Selling-price change (boundary − recorded selling price)", "Cambio del precio de venta (límite − precio de venta registrado)");
    add("OPERAND_TAX_CHANGE", "Tax change on that selling-price change", "Cambio de impuestos por ese cambio del precio de venta");
    add("OPERAND_TAXABLE_BASIS", "Taxable basis", "Base gravable");
    add("OPERAND_TAX_RATE", "Tax rate", "Tasa del impuesto");
    add("OP_AT", "at", "a");
    add("OP_OVER", "over", "durante");
    add("OP_LESS", "less", "menos");
    add("OP_LOWER_OF", "lower of", "el menor de");
    add("NOTE_NO_QUOTE", "No quoted total exists to compare against, so this difference is recorded as zero.", "No existe un total cotizado para comparar; esta diferencia se registra como cero.");
    add("NOTE_POLICY", "Policy setting from {ruleVersion}; not read from a document.", "Valor de la política {ruleVersion}; no procede de un documento.");
    add("NOTE_MONTHLY_FORMULA", "Monthly rate r = APR ÷ 12. Payment = amount financed × r ÷ (1 − (1 + r) to the power of −months), rounded to the cent.", "Tasa mensual r = APR ÷ 12. Pago = monto financiado × r ÷ (1 − (1 + r) elevado a −meses), redondeado al centavo.");
    add("NOTE_FINAL_PAYMENT", "The final payment is the balance those regular payments leave plus one month of interest on it, rounded to the cent.", "El último pago es el saldo que dejan los pagos regulares más un mes de intereses sobre ese saldo, redondeado al centavo.");
    add("NOTE_WALKAWAY", "Walk-away is the lower of the policy maximum and your limit.", "El límite para retirarse es el menor entre el máximo de la política y su límite.");
    add("NOTE_NO_BUYER_LIMIT", "No Buyer limit was entered, so walk-away equals the policy maximum.", "Usted no ingresó un límite; el límite para retirarse equivale al máximo de la política.");
    add("NOTE_NEGOTIATION_DIFFERENCE", "A negative result is shown as zero. Negotiation outcomes are uncertain.", "Un resultado negativo se muestra como cero. Los resultados de la negociación son inciertos.");
    add("NOTE_TAX_CHANGE", "Other offer terms are held fixed; the tax change is the tax recalculated at the recorded rate minus the recorded tax.", "Los demás términos de la oferta se mantienen fijos; el cambio en impuestos equivale al impuesto recalculado con la tasa registrada menos el impuesto registrado.");
    add("NOTE_ZERO_CONTRIBUTION", "No Dealer Offer line contributes, so the amount is zero.", "No contribuye ningún renglón de la oferta del concesionario; el monto es cero.");
    add("NOTE_TAX_COMPONENT", "Each component uses the taxable price after the trade-in allowance, limited by its recorded base cap when present.", "Cada componente usa el precio gravable después del valor en parte de pago, limitado por el tope de base registrado cuando existe.");
    add("NOTE_TAXABLE_BASIS", "Taxable basis = taxable charges {charges} − trade tax credit applied {credit}.", "Base gravable = cargos gravables {charges} − reducción de impuestos aplicada por el vehículo en parte de pago {credit}.");
    add("NOTE_TAX_ROUNDING", "Unrounded tax {amount} {currency}, rounded {mode} to {scale} decimal places.", "Impuesto sin redondear: {amount} {currency}, redondeado {mode} a {scale} decimales.");
    add("ROUND_HALF_UP", "half up", "al más cercano, con los medios hacia arriba");
    add("DESK_PRICE_INCREASED", "Selling price went from {was} to {now}.", "El precio de venta (selling price) pasó de {was} a {now}.");
    add("DESK_DOWN_INCREASED", "Cash down went from {was} to {now}.", "El pago inicial (down payment) pasó de {was} a {now}.");
    add("DESK_TRADE_LOWER", "Trade-in allowance went from {was} to {now}.", "El valor por el vehículo en parte de pago (trade-in) pasó de {was} a {now}.");
    add("DESK_PAYMENT_INCREASED_COST", "Monthly payment went from {was} to {now}, {cost} over {months} months.", "El pago mensual pasó de {was} a {now}: {cost} durante {months} meses.");
    add("DESK_PAYMENT_INCREASED", "Monthly payment went from {was} to {now}.", "El pago mensual pasó de {was} a {now}.");
    add("DESK_APR_INCREASED_COST", "APR went from {was}% to {now}%, about {cost} more in payments over the loan.", "La tasa de porcentaje anual (APR) pasó de {was}% a {now}%: aproximadamente {cost} más en pagos durante el préstamo.");
    add("DESK_APR_INCREASED", "APR went from {was}% to {now}%.", "La tasa de porcentaje anual (APR) pasó de {was}% a {now}%.");
    add("DESK_TERM_CHANGED", "Loan term went from {was} to {now} months.", "El plazo del préstamo pasó de {was} a {now} meses.");
    add("DESK_DISCOUNT_REDUCED", "The discount went from {was} to {now}.", "El descuento pasó de {was} a {now}.");
    add("DESK_REBATE_REDUCED", "The rebate went from {was} to {now}.", "El reembolso del fabricante (rebate) pasó de {was} a {now}.");
    add("DESK_ADDED_CHARGE", "Added charge “{label}”, {amount}, was not in your Agreed Price.", "El cargo agregado «{label}», de {amount}, no estaba en su precio acordado.");
    add("DESK_CREDIT_REMOVED", "Missing: the {label} of {amount} in your Agreed Price.", "Falta «{label}», de {amount}, que figuraba en su precio acordado.");
    add("DESK_PAYMENT_WITHOUT_TOTAL", "This shows a monthly payment but no out-the-door total.", "Se muestra un pago mensual, pero no el precio total final (out-the-door).");
    add("DESK_TOTAL_NOT_SHOWN", "This paperwork shows no out-the-door total.", "Estos documentos no muestran el precio total final (out-the-door).");
    add("DESK_UNKNOWN_ADDON", "{label}, {amount}, is an add-on.", "«{label}», de {amount}, es un producto adicional (add-on).");
    add("DESK_DEALER_FEE", "{label}, {amount}, is a dealer charge.", "«{label}», de {amount}, es un cargo del concesionario.");
    add("DESK_NAMED_ADDON", "{label}, {amount}: {name}. {kind}", "«{label}», {amount}: {name}. {kind}");
    add("DESK_POSSIBLE_NAME", "possibly {name}", "posiblemente {name}");
    add("DESK_COMBINED", "{explanation} {question}", "{explanation} {question}");
    add("DESK_PRICE_QUESTION", "We agreed a selling price of {was}. Why does this say {now}?", "Acordamos un precio de venta de {was}. ¿Por qué aquí dice {now}?");
    add("DESK_DOWN_QUESTION", "We agreed {was} down. Why does this ask for {now}?", "Acordamos un pago inicial de {was}. ¿Por qué aquí se piden {now}?");
    add("DESK_TRADE_QUESTION", "We agreed {was} for my trade-in. Why is it {now} here?", "Acordamos {was} por mi vehículo en parte de pago. ¿Por qué aquí figuran {now}?");
    add("DESK_PAYMENT_QUESTION", "We agreed a payment of {was}. Why is it {now}?", "Acordamos un pago de {was}. ¿Por qué ahora es de {now}?");
    add("DESK_APR_QUESTION", "We agreed {was}% APR. Why is it {now}%?", "Acordamos una tasa de porcentaje anual (APR) de {was}%. ¿Por qué ahora es de {now}%?");
    add("DESK_TERM_QUESTION", "We agreed {was} months. Why is the term {now} months?", "Acordamos {was} meses. ¿Por qué el plazo es de {now} meses?");
    add("DESK_DISCOUNT_QUESTION", "We agreed a discount of {was}. Why is it {now} here?", "Acordamos un descuento de {was}. ¿Por qué aquí figura {now}?");
    add("DESK_REBATE_QUESTION", "We agreed a rebate of {was}. Why is it {now} here?", "Acordamos un reembolso del fabricante de {was}. ¿Por qué aquí figura {now}?");
    add("DESK_ADDED_QUESTION", "The {label} for {amount} was not in the price we agreed. Will you take it off?", "El cargo «{label}» de {amount} no estaba en el precio acordado. ¿Lo quitará?");
    add("DESK_REMOVED_QUESTION", "The {label} of {amount} we agreed is not on this paperwork. Will you put it back?", "El monto acordado de {amount} por «{label}» no aparece en estos documentos. ¿Lo restablecerá?");
    add("DESK_CHARGE_QUESTION", "We agreed {was} for the {label}. Why is it {now} now?", "Acordamos {was} por «{label}». ¿Por qué ahora cuesta {now}?");
    add("DESK_ADDON_QUESTION", "Is the {label} optional? Please show me the price without it.", "¿Es opcional «{label}»? Por favor muestre el precio sin ese producto.");
    add("DESK_FEE_QUESTION", "What is the {label} for, and will you lower or remove it?", "¿Para qué es «{label}»? ¿Lo reducirá o quitará?");
    add("DESK_PAYMENT_TOTAL_QUESTION", "What is the out-the-door total, and what price, APR and term is this payment based on?", "¿Cuál es el precio total final (out-the-door) y en qué precio, tasa de porcentaje anual (APR) y plazo se basa este pago?");
    add("DESK_TOTAL_QUESTION", "What is the out-the-door total, with every fee and tax included?", "¿Cuál es el precio total final (out-the-door), con todos los cargos e impuestos incluidos?");
    add("DESK_CHARGE_INCREASED", "{label} went from {was} to {now}.", "«{label}» pasó de {was} a {now}.");
    add("TRADE_PROVIDER_FIXTURE", "Synthetic trade-in range for development only.", "Rango de valor del vehículo en parte de pago con datos de prueba, solo para desarrollo.");
    add("TRADE_PROVIDER_TIMEOUT", "Trade valuation timed out. No value was substituted.", "Se agotó el tiempo de la valoración del vehículo en parte de pago. No se sustituyó ningún valor.");
    add("TRADE_PROVIDER_LIMIT", "Trade valuation request limit reached. No value was substituted.", "Se alcanzó el límite de solicitudes de valoración del vehículo en parte de pago. No se sustituyó ningún valor.");
    add("TRADE_PROVIDER_FIELDS", "Trade valuation unavailable because required vehicle facts are missing.", "La valoración del vehículo en parte de pago no está disponible porque faltan datos obligatorios del vehículo.");
    add("TRADE_PROVIDER_STALE", "Trade valuation unavailable because the observation is stale.", "La valoración del vehículo en parte de pago no está disponible porque la observación está desactualizada.");
    add("TRADE_PROVIDER_INVALID", "Trade valuation unavailable because the provider response was invalid.", "La valoración del vehículo en parte de pago no está disponible porque la respuesta del proveedor no era válida.");
    add("TRADE_PROVIDER_UNAVAILABLE", "Trade valuation provider unavailable. No value was substituted.", "El proveedor de valoración del vehículo en parte de pago no está disponible. No se sustituyó ningún valor.");
    add("AMOUNT_VEHICLE_SUBTOTAL", "Vehicle subtotal", "Subtotal del vehículo");
    add("AMOUNT_DEALER_CHARGES", "Dealer charges", "Cargos del concesionario");
    add("AMOUNT_GOVERNMENT_CHARGES", "Government charges", "Cargos del gobierno");
    add("AMOUNT_UNKNOWN_CHARGES", "Unclassified charges", "Cargos sin clasificar");
    add("AMOUNT_TAXES", "Taxes", "Impuestos");
    add("AMOUNT_TRADE_ALLOWANCE", "Trade allowance", "Valor por el vehículo en parte de pago (trade-in)");
    add("AMOUNT_TRADE_PAYOFF", "Trade payoff", "Saldo del préstamo (payoff) del vehículo en parte de pago");
    add("AMOUNT_TRADE_NET", "Net trade credit", "Crédito neto por el vehículo en parte de pago");
    add("AMOUNT_DOWN_PAYMENT", "Down payment", "Pago inicial (down payment)");
    add("AMOUNT_CREDITS", "Credits and other Buyer funds", "Créditos y otros fondos del comprador");
    add("AMOUNT_DISCOUNTS", "Dealer discounts", "Descuentos del concesionario");
    add("AMOUNT_REBATES", "Verified rebates", "Reembolsos del fabricante (rebates) verificados");
    add("AMOUNT_REBATES_EXCLUDED", "Conditional rebates excluded", "Reembolsos del fabricante condicionales excluidos");
    add("AMOUNT_QUOTED_TOTAL", "Dealer's quoted out-the-door total", "Precio total final (out-the-door) cotizado por el concesionario");
    add("AMOUNT_RECOMPUTED_TOTAL", "Recomputed out-the-door total", "Precio total final recalculado");
    add("AMOUNT_TOTAL_DIFFERENCE", "Quoted and calculated total difference", "Diferencia entre el total cotizado y el calculado");
    add("AMOUNT_TOLERANCE", "Recorded reconciliation tolerance", "Tolerancia registrada de conciliación");
    add("AMOUNT_PLAN_SELLING_OPENING", "Approved opening selling price", "Precio de venta inicial aprobado");
    add("AMOUNT_PLAN_SELLING_TARGET", "Approved target selling price", "Precio de venta objetivo aprobado");
    add("AMOUNT_PLAN_SELLING_MAXIMUM", "Selling-price policy maximum", "Precio de venta máximo según la política");
    add("AMOUNT_PLAN_SELLING_WALKAWAY", "Approved selling-price walk-away limit", "Límite aprobado para retirarse según el precio de venta");
    add("AMOUNT_PLAN_OTD_OPENING", "Approved opening out-the-door total", "Precio total final inicial aprobado");
    add("AMOUNT_PLAN_OTD_TARGET", "Approved target out-the-door total", "Precio total final objetivo aprobado");
    add("AMOUNT_PLAN_OTD_MAXIMUM", "Out-the-door policy maximum", "Precio total final máximo según la política");
    add("AMOUNT_PLAN_OTD_WALKAWAY", "Approved out-the-door walk-away limit", "Límite aprobado para retirarse según el precio total final");
    add("AMOUNT_BUYER_SELLING_LIMIT", "Your selling-price limit", "Su límite del precio de venta");
    add("AMOUNT_BUYER_OTD_LIMIT", "Your out-the-door limit", "Su límite del precio total final");
    add("AMOUNT_SAVINGS_BASELINE", "Recorded comparison baseline", "Total de referencia registrado para la comparación");
    add("AMOUNT_SAVINGS_DIFFERENCE", "Recorded difference from the comparison baseline", "Diferencia registrada frente al total de referencia");
    add("SOURCE_NONE", "No document source or calculation is recorded for this amount.", "No se registró una fuente documental ni un cálculo para este monto.");
    add("SOURCE_NO_REGION", "No document region is recorded for this line.", "No se registró una región del documento para este renglón.");
    add("SOURCE_NO_TOTAL", "The Dealer Offer has no quoted out-the-door total on a document.", "La oferta del concesionario no tiene un precio total final cotizado en un documento.");
    add("SOURCE_BUYER_FINANCING", "Buyer-confirmed financing entry; no document region is recorded for it.", "Dato de financiamiento confirmado por usted; no tiene una región del documento registrada.");
    add("SOURCE_MARKET", "Market estimate from {provider}; not read from a dealer document.", "Estimación de mercado de {provider}; no procede de un documento del concesionario.");
    add("SOURCE_PLAN_BUYER", "Limit you entered in the Approved Baseline version {version}; not read from a dealer document.", "Límite que usted ingresó en la versión de referencia aprobada {version}; no procede de un documento del concesionario.");
    add("SOURCE_PLAN_CALCULATED", "Calculated in the Approved Baseline version {version}; not read from a dealer document.", "Calculado en la versión de referencia aprobada {version}; no procede de un documento del concesionario.");
    add("EXPLAIN_CANNOT_VERIFY", "This report cannot verify the deal against the approved terms.", "Este informe no puede verificar la oferta frente a los términos aprobados.");
    add("EXPLAIN_REVIEW", "The financial checks require review before you decide.", "Las comprobaciones financieras requieren revisión antes de que usted decida.");
    add("EXPLAIN_MATCH", "The recorded financial checks match the approved deal within their stated scope.", "Las comprobaciones financieras registradas coinciden con la oferta aprobada dentro de su alcance declarado.");
    add("EXPLAIN_PAYMENT", "The quoted payment differs from the calculated payment beyond the recorded tolerance.", "El pago cotizado difiere del pago calculado más allá de la tolerancia registrada.");
    add("EXPLAIN_FINANCING", "The report provides calculated financing costs for the verified inputs and recorded schedule.", "El informe presenta costos de financiamiento calculados con los datos verificados y el calendario registrado.");
    add("EXPLAIN_ADDONS", "The verified financing inputs include financed add-ons.", "Los datos verificados de financiamiento incluyen productos adicionales financiados.");
    add("EXPLAIN_FINANCING_UNKNOWN", "Financing costs are unavailable for reliance in this report.", "Los costos de financiamiento no están disponibles para basarse en ellos en este informe.");
    add("EXPLAIN_CASH_STALE", "The saved cash calculation is stale or unverified for this report.", "El cálculo guardado de la compra al contado está desactualizado o sin verificar para este informe.");
    add("EXPLAIN_MARKET_UNKNOWN", "A market comparison is unavailable; this report does not establish a market price advantage.", "La comparación de mercado no está disponible; este informe no establece una ventaja de precio de mercado.");
    add("EXPLAIN_MARKET_ABOVE", "The verified selling price is above the estimated market selling-price range. This is not an out-the-door comparison.", "El precio de venta verificado está por encima del rango estimado de precios de venta de mercado. Esta comparación excluye los cargos adicionales de compra.");
    add("GROUNDED_SCOPE", "These explanations concern financial consistency only. They do not establish legal compliance, mechanical condition, title, vehicle history or purchase safety. Savings are not guaranteed.", "Estas explicaciones abarcan solo la coherencia financiera. No evalúan el cumplimiento legal, el estado mecánico, el título, el historial del vehículo ni la seguridad de la compra. No se prometen ahorros.");
    add("REPLY_SCOPE", "This reply states only figures recorded in this report and stays within your approved limits. You edit and send it yourself; the product does not contact the Dealer. It does not establish legal compliance, mechanical condition, title, vehicle history or purchase safety.", "Esta respuesta contiene solo cifras registradas en el informe y respeta sus límites aprobados. Usted la edita y envía; el producto no contacta al concesionario. No evalúa el cumplimiento legal, el estado mecánico, el título, el historial del vehículo ni la seguridad de la compra.");
    add("COMPARISON_BASELINE", "BASELINE", "la versión de referencia");
    add("COMPARISON_PREVIOUS", "PREVIOUS", "la oferta anterior");
    add("REVISION_UNCERTAIN", "Unresolved classification or unverified value requires clarification.", "Una clasificación pendiente o un valor sin verificar requiere aclaración.");
    add("REVISION_ADDED", "The term is added relative to {comparison}.", "El término se agregó respecto de {comparison}.");
    add("REVISION_MISSING", "The term is missing relative to {comparison}.", "El término falta respecto de {comparison}.");
    add("REVISION_CHANGED", "The term is changed relative to {comparison}.", "El término cambió respecto de {comparison}.");
    add("REVISION_ASK_VERIFY", "Ask the dealer to clarify and verify this term.", "Pida al concesionario que aclare y verifique este término.");
    add("REVISION_ASK_REVIEW", "Review this difference with the dealer before accepting.", "Revise esta diferencia con el concesionario antes de aceptar.");
    add("REVISION_BOTH_REQUIRED", "Both offer calculations in the same currency are required; missing values are not zero.", "Se requieren los cálculos de ambas ofertas en la misma moneda; los valores faltantes no equivalen a cero.");
    add("REVISION_CASH_BASIS", "Complete pre-tax OTD difference, including fees, add-ons and reductions; tax effect is the difference between separately calculated taxes.", "Diferencia completa del precio total final antes de impuestos, incluidos cargos, productos adicionales y reducciones; el efecto en impuestos es la diferencia entre los impuestos calculados por separado.");
    add("REVISION_SCOPE", "Frozen verified document terms against Approved Baseline and immediately preceding offer; cash deltas use recorded calculations. Financing schedule and purchase safety are excluded.", "Términos verificados guardados frente a la versión de referencia aprobada y la oferta inmediatamente anterior; las diferencias de compra al contado usan cálculos registrados. Se excluyen el calendario de financiamiento y la seguridad de la compra.");
    add("REVISION_LIMIT", "The revised offer exceeds the approved ceiling. Dealer changes cannot raise Buyer limits.", "La oferta revisada supera el límite aprobado. Los cambios del concesionario no pueden aumentar sus límites.");
    add("REVISION_REDUCE", "Ask the dealer to reduce the offer within the approved limit.", "Pida al concesionario que reduzca la oferta para que quede dentro del límite aprobado.");
    add("WORKSHEET_MISSING_BOTH", "The four-square worksheet shows a monthly payment but the out-the-door total and loan term are missing; payment-led figures hide the total, so ask for an itemized total and the term in months and do not infer either from payment.", "La hoja de cuatro cuadros (four-square) muestra un pago mensual, pero faltan el precio total final (out-the-door) y el plazo del préstamo; las cifras centradas en el pago ocultan el total. Pida el total desglosado y el plazo en meses; no deduzca ninguno a partir del pago.");
    add("WORKSHEET_MISSING_TOTAL", "The four-square worksheet shows a monthly payment but the out-the-door total is missing. Payment-led figures hide the total; ask for an itemized out-the-door total. Do not infer it from payment.", "La hoja de cuatro cuadros (four-square) muestra un pago mensual, pero falta el precio total final (out-the-door). Las cifras centradas en el pago ocultan el total; pida el precio total final desglosado. No lo deduzca a partir del pago.");
    add("WORKSHEET_MISSING_TERM", "The four-square worksheet loan term is missing. Ask for the term in months; do not infer it from payment.", "Falta el plazo del préstamo en la hoja de cuatro cuadros (four-square). Pida el plazo en meses; no lo deduzca a partir del pago.");
    add("PDF_FURTHER_UNKNOWN", "{count} further findings: unknown in this saved report.", "Otros hallazgos: {count}, desconocidos en este informe guardado.");
    add("FL_FEE_NOTE", "A Florida tax collector or private tag agency may add its own service charges, which it must itemize (F.S. 319.32(2)(c) and 320.04(3)). They are not in this schedule.", "Un recaudador de impuestos de Florida o una agencia privada de placas puede agregar sus propios cargos de servicio, que debe desglosar (F.S. 319.32(2)(c) y 320.04(3)). No se incluyen en esta tabla.");
    add("TX_FEE_NOTE", "For a new car the dealer collects 24 months of registration. The TxDMV pages read do not state the 24-month amount, so this schedule allows two years of the base and county fees.", "Para un vehículo nuevo, el concesionario cobra 24 meses de registro. Las páginas consultadas del TxDMV no indican el monto de los 24 meses; esta tabla permite dos años de los cargos básicos y del condado.");
    add("NC_FEE_NOTE", "G.S. 20-85 prints $25.00 for a plate transfer and a lien; NCDOT's 2024 fee adjustment lists $25.50. This schedule uses $25.50.", "G.S. 20-85 indica $25.00 para transferir una placa y registrar un prestamista; el ajuste de cargos de 2024 del NCDOT indica $25.50. Esta tabla usa $25.50.");
    add("MARKET_NO_ASSESSMENT", "Market evidence is unavailable: no market assessment was recorded. No market rating is drawn.", "La información de mercado no está disponible: no se registró un análisis de mercado. No se asigna una evaluación de mercado.");
    add("MARKET_RETRIEVED", "Market data retrieved: {date}.", "Datos de mercado consultados el {date}.");
    add("MARKET_RETRIEVAL_UNKNOWN", "Market data retrieval time is unavailable.", "La fecha de consulta de los datos de mercado no está disponible.");
    add("MARKET_OBSERVATION_UNKNOWN", "Observation window is unavailable.", "El período de observación no está disponible.");
    add("MARKET_OBSERVATION", "Observation window: {window}.", "Período de observación: {window}.");
    add("MARKET_STALE", "Market evidence is unavailable: the stored snapshot is stale or has an invalid retrieval time. No market rating is drawn.", "La información de mercado no está disponible: los datos guardados están desactualizados o su fecha de consulta no es válida. No se asigna una evaluación de mercado.");
    add("MARKET_TOO_FEW", "Too few compatible, current comparables ({count}) to support a market conclusion. No market rating is drawn.", "Hay muy pocos vehículos comparables compatibles y vigentes ({count}) para sustentar una conclusión de mercado. No se asigna una evaluación de mercado.");
    add("MARKET_DIFFERENT_VEHICLE", "Market evidence does not match the current confirmed vehicle. No market rating is drawn.", "La información de mercado no corresponde al vehículo confirmado actual. No se asigna una evaluación de mercado.");
    add("MARKET_CONFIDENCE", "Uncertainty: {confidence} confidence from {count} usable comparables. This is an estimated selling-price range.", "Incertidumbre: confianza {confidence} a partir de {count} vehículos comparables utilizables. Este es un rango estimado de precios de venta.");
    add("MARKET_FIXTURE", "Development fixture only. No licensed market source is integrated.", "Solo datos de prueba para desarrollo. No hay una fuente de mercado con licencia integrada.");
    add("MARKET_MINIMUM_SAMPLE", "At least three compatible, current observations are required.", "Se requieren al menos tres observaciones compatibles y vigentes.");
    add("MARKET_DIVERGENT", "Observed prices diverge; the range is widened and confidence reduced.", "Los precios observados difieren; el rango se amplía y la confianza se reduce.");
    add("MARKET_SPARSE", "The usable sample is sparse; the range includes a $500 uncertainty buffer.", "La muestra utilizable es pequeña; el rango incluye un margen de incertidumbre de $500.");
    add("SCOPE_MARKET_PROVIDER", "Market provider: {provider}; {limitation}", "Proveedor de mercado: {provider}; {limitation}");
    add("SCOPE_MARKET_NONE", "Market provider: unavailable", "Proveedor de mercado: no disponible");
    add("SCOPE_JURISDICTION", "Jurisdiction coverage: {jurisdiction} using {ruleVersion}. {note}", "Cobertura de la jurisdicción: {jurisdiction} según {ruleVersion}. {note}");
    add("SCOPE_JURISDICTION_UNKNOWN", "Jurisdiction coverage: unavailable.", "Cobertura de la jurisdicción: no disponible.");
    add("SCOPE_JURISDICTION_NO_NOTE", "Jurisdiction coverage: {jurisdiction} using {ruleVersion}.", "Cobertura de la jurisdicción: {jurisdiction} según {ruleVersion}.");
    add("NC_TITLING_TAX", "North Carolina collects the highway use tax when the car is titled. The dealer may collect it with the sale, or you pay it when you title the car, so a quote can leave it out. It is still owed, and the total here includes it.", "Carolina del Norte cobra el impuesto por uso de carreteras (highway use tax) al tramitar el título del vehículo. El concesionario puede cobrarlo con la venta, o usted lo paga al tramitar el título, por lo que una cotización puede omitirlo. Aun así se debe pagar y el total de este informe lo incluye.");
    add("MARKET_NO_USABLE", "The provider supplied no usable evidence.", "El proveedor no proporcionó información utilizable.");
    add("MARKET_DETAIL_UNAVAILABLE", "Market evidence is unavailable. {limitation} No market rating is drawn.", "La información de mercado no está disponible. {limitation} No se asigna una evaluación de mercado.");
    add("CONFIDENCE_LOW", "low", "baja");
    add("CONFIDENCE_MEDIUM", "medium", "media");
    add("CONFIDENCE_HIGH", "high", "alta");
    add("NO_LIMITATION", "No additional limitation recorded.", "No se registró otra limitación.");
    add("TACTIC_PRINCIPAL_EXPLICIT", "Worksheet amount financed; ordinary monthly payments starting one month after funding", "Monto financiado de la hoja; pagos mensuales estándar a partir de un mes después del desembolso");
    add("TACTIC_PRINCIPAL_DIFFERENCE", "Worksheet selling price less stated cash down; excludes taxes, fees and products outside those figures; ordinary monthly schedule assumed", "Precio de venta de la hoja menos el pago inicial indicado; excluye impuestos, cargos y productos fuera de esas cifras; supone un calendario mensual estándar");
    add("TACTIC_PRINCIPAL_UNKNOWN", "Worksheet principal unavailable; no trade or missing amounts inferred", "Monto principal de la hoja no disponible; no se infieren datos del vehículo en parte de pago ni montos faltantes");
    add("TACTIC_PAYMENT_BASIS", "{principal}; quoted monthly difference multiplied by term; actual final payment and timing may differ.", "{principal}; la diferencia mensual cotizada se multiplica por el plazo; el último pago y las fechas reales pueden variar.");
    add("TACTIC_TERM_BASIS", "{principal}; same principal and APR at each term, including final-payment rounding.", "{principal}; el mismo monto principal y la misma tasa de porcentaje anual (APR) en cada plazo, incluido el redondeo del último pago.");
    add("TACTIC_APR_BASIS", "{principal}; APR-only comparison at the worksheet term, excluding differences in lender fees and timing.", "{principal}; comparación solo de la tasa de porcentaje anual (APR) con el plazo de la hoja; excluye diferencias en cargos del prestamista y fechas.");
    add("TACTIC_DEALER_APR_BASIS", "{principal}; APR-only comparison at the worksheet term, excluding differences in lender fees and timing. Negative amounts are what the dealer's financing costs less than your own offer.", "{principal}; comparación solo de la tasa de porcentaje anual (APR) con el plazo de la hoja; excluye diferencias en cargos del prestamista y fechas. Los montos negativos indican cuánto menos cuesta el financiamiento del concesionario que su propia oferta.");
    add("DRAFT_CURRENT_OFFER", "Could you provide a current itemized written offer?", "¿Puede proporcionar una oferta escrita, actual y desglosada?");
    add("DRAFT_CURRENT_TERMS", "Could you walk me through the current written terms and any changes?", "¿Puede explicar los términos escritos actuales y los cambios?");
    add("DRAFT_CONFIRM_OFFER", "Could you confirm whether this exact written offer is still available?", "¿Puede confirmar si esta misma oferta escrita sigue disponible?");
    add("DRAFT_RECONCILE_PAYMENT", "Could you reconcile the quoted payment with the recorded loan terms and payment schedule?", "¿Puede explicar cómo concuerda el pago cotizado con los términos del préstamo y el calendario de pagos registrados?");
    add("DRAFT_SCHEDULE", "Could you confirm the full payment schedule and full-term cost in writing?", "¿Puede confirmar por escrito el calendario completo de pagos y el costo durante todo el plazo?");
    add("DRAFT_WITHOUT_ADDONS", "Could you provide an itemized quote without the financed add-ons for comparison?", "¿Puede proporcionar una cotización desglosada sin los productos adicionales financiados para comparar?");
    add("DRAFT_FINANCE_INPUTS", "Could you provide complete current financing terms and the payment schedule?", "¿Puede proporcionar los términos completos y actuales del financiamiento y el calendario de pagos?");
    add("DRAFT_ITEMIZE", "Could you itemize the current purchase price, taxes, fees and total due?", "¿Puede desglosar el precio de compra actual, los impuestos, los cargos y el total por pagar?");
    add("DRAFT_FLEXIBILITY", "Is there flexibility in the written selling price?", "¿Se puede negociar el precio de venta escrito?");
    add("DRAFT_LOWER", "Would you consider a lower selling price in a revised written offer?", "¿Consideraría un precio de venta menor en una oferta escrita revisada?");
    add("DRAFT_COUNTER", "Would you consider USD {price} as the selling price in a revised written offer?", "¿Consideraría USD {price} como precio de venta en una oferta escrita revisada?");
    add("DRAFT_THANKS", "Thank you for your response.", "Gracias por su respuesta.");
    add("DRAFT_SELLING_PRICE", "The current written offer lists a selling price of {amount}.", "La oferta escrita actual indica un precio de venta de {amount}.");
    add("DRAFT_OTD", "The current written offer lists an out-the-door total of {amount}.", "La oferta escrita actual indica un precio total final (out-the-door) de {amount}.");
    add("DRAFT_MARKET", "Recorded market estimates for this vehicle range from {low} to {high} in selling price.", "Las estimaciones de mercado registradas para este vehículo van de {low} a {high} en precio de venta.");
    add("DRAFT_PAYMENT", "The quoted monthly payment does not match the payment calculated from the written loan terms.", "El pago mensual cotizado no coincide con el pago calculado a partir de los términos escritos del préstamo.");
    add("DRAFT_PROPOSAL", "I would consider a revised written offer with a selling price of {amount}.", "Consideraría una oferta escrita revisada con un precio de venta de {amount}.");
    add("DRAFT_REVISED_ITEMIZED", "Could you send a revised itemized written offer at that selling price, including the out-the-door total?", "¿Puede enviar una oferta escrita revisada y desglosada con ese precio de venta, incluido el precio total final?");
    add("DRAFT_CURRENT_ITEMIZED", "Could you send a current itemized written offer, including the out-the-door total?", "¿Puede enviar una oferta escrita actual y desglosada, incluido el precio total final?");
    add("DRAFT_WRITTEN_PAYMENT", "Could you reconcile the quoted payment with the written loan terms and payment schedule?", "¿Puede explicar cómo concuerda el pago cotizado con los términos escritos del préstamo y el calendario de pagos?");
    add("ADDON_UNKNOWN_QUESTION", "What is the “{label}”, and is it optional?", "¿Qué es «{label}» y es opcional?");
    add("ADDON_LISTING", "The listing you saved showed {advertised} for the car. This paperwork adds {amount} in add-ons and extra dealer charges beside the vehicle price. Ask whether the advertised price included them.", "El anuncio guardado mostraba {advertised} por el vehículo. Estos documentos agregan {amount} en productos adicionales y cargos del concesionario, además del precio del vehículo. Pregunte si el precio anunciado los incluía.");
    add("STATE_FEE_ABOVE_SCHEDULE", "The state fee line “{label}” is {amount}; the schedule lists at most {highest}. Ask for an itemization; fees excluded from the comparison may also apply. Schedule {ruleVersion}.", "El cargo estatal «{label}» es de {amount}; la tabla enumera como máximo {highest}. Pida un desglose; también pueden corresponder cargos excluidos de la comparación. Tabla {ruleVersion}.");
    add("STATE_FEE_LINES_ABOVE_SCHEDULE", "The state fee lines total {amount}; the schedule lists at most {highest}. Ask for an itemization; fees excluded from the comparison may also apply. Schedule {ruleVersion}.", "Los cargos estatales suman {amount}; la tabla enumera como máximo {highest}. Pida un desglose; también pueden corresponder cargos excluidos de la comparación. Tabla {ruleVersion}.");
    add("STATE_FEE_DEALER_CHARGE_LABEL", "“{label}” is listed with state fees but names a dealer charge. Ask the dealer to separate it from the state fees.", "«{label}» figura entre los cargos estatales, pero es el nombre de un cargo del concesionario. Pida que lo separe de los cargos estatales.");
    add("STATE_FEE_MIXED_LABEL", "“{label}” names both a dealer charge and a state fee. Ask for them separately.", "«{label}» nombra tanto un cargo del concesionario como un cargo estatal. Pida que los muestre por separado.");
    add("STATE_FEE_NO_AMOUNT", "The amount of “{label}” could not be read; it was not checked.", "No se pudo leer el monto de «{label}»; no se comprobó.");
    add("STATE_FEE_TAX_COVERED", "“{label}” is covered by the tax check and is not counted again here.", "«{label}» está incluido en la comprobación de impuestos y no se vuelve a contar aquí.");
    add("STATE_FEE_LISTED_AS_DEALER_CHARGE", "“{label}” names a state fee but is listed with dealer charges. Ask whether it is passed through or set by the dealer.", "«{label}» nombra un cargo estatal, pero figura entre los cargos del concesionario. Pregunte si se transfiere al estado o si lo fija el concesionario.");
    add("STATE_FEE_NOT_IN_SCHEDULE", "The state schedule has no fee named “{label}”. A dealer may also pass through a fee the schedule does not list.", "La tabla estatal no enumera un cargo llamado «{label}». El concesionario también puede transferir un cargo que no aparezca en la tabla.");
    add("STATE_FEE_NO_TOP_AMOUNT", "“{label}” depends on facts for which this schedule has no maximum comparison amount.", "«{label}» depende de datos para los cuales esta tabla no tiene un monto máximo de comparación.");
    add("STATE_FEE_WITHIN_RANGE", "“{label}”, {amount}, is within the schedule's maximum of {highest}. The applicable fee depends on the vehicle and transaction; this does not establish the exact amount due.", "«{label}», de {amount}, está dentro del máximo de {highest} de la tabla. El cargo aplicable depende del vehículo y la operación; esto no establece el monto exacto por pagar.");
    add("STATE_FEE_CHECKED", "State fees total {amount} and are within the listed schedule. Schedule {ruleVersion}.", "Los cargos estatales suman {amount} y están dentro de la tabla publicada. Tabla {ruleVersion}.");
    add("STATE_FEE_NOT_CHECKED", "State fees total {amount}; no current schedule is available for this case, so they were not checked.", "Los cargos estatales suman {amount}; no hay una tabla vigente para este caso, por lo que no se comprobaron.");
    add("STATE_FEE_ITEMIZE", "Please itemize the state fees on this line.", "Por favor desglose los cargos estatales de este renglón.");
    add("STATE_FEE_ITEMIZE_PAPERWORK", "Please itemize the state fees on this paperwork.", "Por favor desglose los cargos estatales de estos documentos.");
    add("STATE_FEE_QUESTION", "Please explain what “{label}” covers and how its amount was set.", "Por favor explique qué cubre «{label}» y cómo se fijó su monto.");
    add("DOC_FEE_TX", "The documentary fee lines {labels} total {amount}. Possible documentary fee aliases total {possible}; these are not an established documentary fee amount. Texas has a $225 safe harbor. Before charging more the dealer must file notification and supporting cost analysis with OCCC. A higher amount alone does not establish a breach. Rule {ruleVersion}; checked {checked}; review by {reviewBy}. {citation}.", "Los renglones {labels} identificados como cargo por documentación (doc fee) suman {amount}. Los posibles cargos por documentación bajo otros nombres suman {possible}; estos no constituyen un monto establecido del cargo. Texas tiene un umbral de referencia de $225. Antes de cobrar más, el concesionario debe presentar una notificación y un análisis de costos de respaldo ante la OCCC. Un monto mayor por sí solo no establece un incumplimiento. Regla {ruleVersion}; comprobada el {checked}; revisar antes del {reviewBy}. {citation}.");
    add("DOC_FEE_FL", "The documentary fee lines {labels} total {amount}; possible aliases total {possible} and need clarification. Florida sets no dollar cap on this dealer fee. Required dealer charges belong in the advertised price, subject to the joint-advertising exception. Predelivery-service disclosures must explain dealer costs and profit including document preparation; this check does not assess those disclosures. The fee is negotiable. Rule {ruleVersion}; checked {checked}; review by {reviewBy}. {citation}.", "Los renglones {labels} identificados como cargo por documentación (doc fee) suman {amount}; los posibles cargos bajo otros nombres suman {possible} y requieren aclaración. Florida no fija un límite monetario para este cargo del concesionario. Los cargos obligatorios del concesionario forman parte del precio anunciado, sujetos a la excepción de publicidad conjunta. La divulgación del servicio previo a la entrega debe explicar los costos y la ganancia del concesionario, incluida la preparación de documentos; esta comprobación no evalúa esas divulgaciones. El cargo es negociable. Regla {ruleVersion}; comprobada el {checked}; revisar antes del {reviewBy}. {citation}.");
    add("DOC_FEE_NC", "The documentary fee lines {labels} total {amount}; possible aliases total {possible} and need clarification. North Carolina sets no dollar cap on this dealer fee. It must be posted at the dealership, disclosed in vehicle-price advertising and identified separately on the order or bill of sale. This check does not assess those disclosures. The fee is negotiable. Rule {ruleVersion}; checked {checked}; review by {reviewBy}. {citation}.", "Los renglones {labels} identificados como cargo por documentación (doc fee) suman {amount}; los posibles cargos bajo otros nombres suman {possible} y requieren aclaración. Carolina del Norte no fija un límite monetario para este cargo del concesionario. Debe publicarse en el establecimiento, divulgarse en los anuncios de precios e identificarse por separado en la orden o factura de venta. Esta comprobación no evalúa esas divulgaciones. El cargo es negociable. Regla {ruleVersion}; comprobada el {checked}; revisar antes del {reviewBy}. {citation}.");
    add("DOC_FEE_QUESTION", "Please explain the documentary fee lines {labels}, including possible aliases, and whether the amount can be reduced. For a Texas fee above $225, has the OCCC filing with supporting cost analysis been made?", "Por favor explique los renglones {labels} del cargo por documentación, incluidos los posibles nombres alternativos, y si se puede reducir el monto. Para un cargo en Texas mayor de $225, ¿se presentó ante la OCCC la notificación con el análisis de costos de respaldo?");
  }
  private FindingText() {}
  private static void loadGovernmentFees() {
    try (var english = FindingText.class.getResourceAsStream("/fees/government-fees.v1.json");
         var spanish = FindingText.class.getResourceAsStream("/i18n/government-fees.es.json")) {
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      var translations = json.readTree(spanish);
      for (var state : json.readTree(english).path("states")) for (var fee : state.path("fees")) {
        String id = fee.path("id").asText();
        var translated = translations.path(id);
        if (translated.size() != 2) throw new IllegalStateException("Missing Spanish state fee: " + id);
        add("STATE_" + id + "_NAME", fee.path("name").asText(), translated.get(0).asText());
        add("STATE_" + id + "_PURPOSE", fee.path("purpose").asText(), translated.get(1).asText());
      }
    } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot load Spanish state fees", failure); }
  }
  private static void loadAddOns() {
    try (var english = FindingText.class.getResourceAsStream("/fees/dealer-add-ons.v1.json");
         var spanish = FindingText.class.getResourceAsStream("/i18n/dealer-add-ons.es.json")) {
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      var translations = json.readTree(spanish);
      for (var entry : json.readTree(english).path("entries")) {
        String id = entry.path("id").asText();
        var translated = translations.path(id);
        if (translated.size() != 3) throw new IllegalStateException("Missing Spanish add-on: " + id);
        String[] fields = {"name", "what", "question"};
        for (int i = 0; i < fields.length; i++) add("ADDON_" + id.replace('-', '_').toUpperCase(java.util.Locale.ROOT)
            + "_" + fields[i].toUpperCase(java.util.Locale.ROOT), entry.path(fields[i]).asText(), translated.get(i).asText());
      }
    } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot load Spanish add-ons", failure); }
  }
  private static void add(String code, String en, String es) {
    var names = new java.util.ArrayList<String>();
    var pattern = new StringBuilder("\\A");
    var matcher = Pattern.compile("\\{(\\w+)\\}").matcher(en);
    int last = 0;
    while (matcher.find()) {
      pattern.append(Pattern.quote(en.substring(last, matcher.start()))).append("(.*?)");
      names.add(matcher.group(1)); last = matcher.end();
    }
    pattern.append(Pattern.quote(en.substring(last))).append("\\z");
    TEMPLATES.put(code, new Template(code, en, es, Pattern.compile(pattern.toString(), Pattern.DOTALL), java.util.List.copyOf(names)));
  }
  public static java.util.List<Finding> templates() {
    return TEMPLATES.values().stream().map(t -> new Finding(t.code(), Map.of(), t.en())).toList();
  }

  public static Finding identify(String text) {
    for (var template : TEMPLATES.values()) {
      if (template.en().matches("\\{\\w+\\}") || java.util.Set.of("DESK_COMBINED", "DESK_NAMED_ADDON").contains(template.code())) continue;
      var match = template.pattern().matcher(text);
      if (match.matches()) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int i = 0; i < template.names().size(); i++) parameters.put(template.names().get(i), parameter(template.names().get(i), match.group(i + 1)));
        return new Finding(template.code(), parameters, text);
      }
    }
    return null;
  }

  private static Object parameter(String name, String value) {
    if (java.util.Set.of("label", "labels", "listingVin", "paperworkVin", "recordedVin", "provider", "jurisdiction", "ruleVersion", "citation", "source").contains(name)) return value;
    if (java.util.Set.of("principal", "limitation", "note", "confidence", "comparison", "equityBasis", "reference", "mode").contains(name)) {
      if (value.equals("null")) return new Finding("NO_LIMITATION", Map.of(), "No additional limitation recorded.");
      var nested = identify(value);
      if (nested != null) return nested;
    }
    if (name.equals("window") && value.contains(" to ")) {
      try {
        var parts = value.split(" to ", 2);
        var format = java.time.format.DateTimeFormatter.ofPattern("MMMM d, uuuu", java.util.Locale.US);
        var end = java.time.LocalDate.parse(parts[1], format);
        var start = java.time.LocalDate.parse(parts[0].contains(",") ? parts[0] : parts[0] + ", " + end.getYear(), format);
        return Map.of("fromDate", start.toString(), "toDate", end.toString());
      } catch (java.time.format.DateTimeParseException invalid) { /* The stored wording remains the fallback. */ }
    }
    if (value.matches("-?\\$[0-9,]+(?:\\.[0-9]{2})?")) {
      var amount = new java.math.BigDecimal(value.replace("$", "").replace(",", ""));
      return Map.of("minorUnits", amount.movePointRight(2).longValueExact(), "currency", "USD");
    }
    if (value.matches("-?[0-9]+(?:\\.[0-9]+)?")) return new java.math.BigDecimal(value);
    try {
      var date = java.time.LocalDate.parse(value, java.time.format.DateTimeFormatter.ofPattern("MMMM d, uuuu", java.util.Locale.US));
      return Map.of("date", date.toString());
    } catch (java.time.format.DateTimeParseException notDate) { return value; }
  }

  private static String formatted(Object value, String language) {
    if (value instanceof Finding finding) return render(language, finding);
    if (value instanceof Map<?, ?> map) {
      if (map.get("fromDate") != null) return formatted(Map.of("date", map.get("fromDate")), language)
          + ("es".equals(language) ? " a " : " to ") + formatted(Map.of("date", map.get("toDate")), language);
      if (map.get("code") != null) {
        @SuppressWarnings("unchecked") var parameters = (Map<String, Object>) map.get("parameters");
        return render(language, new Finding(map.get("code").toString(), parameters == null ? Map.of() : parameters,
            String.valueOf(map.get("text"))));
      }
      if (map.get("items") instanceof java.util.List<?> items) {
        String mode = String.valueOf(map.get("mode"));
        String separator = mode.equals("sentences") ? " " : "es".equals(language) ? mode.equals("or") ? " o " : " y " : mode.equals("or") ? " or " : " and ";
        return items.stream().map(item -> formatted(item, language)).collect(java.util.stream.Collectors.joining(separator));
      }
      if (map.get("minorUnits") instanceof Number amount) {
        var format = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.forLanguageTag("es".equals(language) ? "es-US" : "en-US"));
        format.setCurrency(java.util.Currency.getInstance(map.get("currency").toString()));
        return format.format(java.math.BigDecimal.valueOf(amount.longValue(), 2));
      }
      if (map.get("date") != null) return java.time.LocalDate.parse(map.get("date").toString()).format(
          java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG)
              .withLocale(java.util.Locale.forLanguageTag("es".equals(language) ? "es-US" : "en-US")));
    }
    return String.valueOf(value);
  }

  public static String render(String language, Finding finding) {
    var template = TEMPLATES.get(finding.code());
    if (template == null) return finding.text();
    String text = "es".equals(language) ? template.es() : template.en();
    for (var parameter : finding.parameters().entrySet())
      text = text.replace("{" + parameter.getKey() + "}", parameter.getKey().equals("status") && "es".equals(language)
          ? switch (String.valueOf(parameter.getValue())) {
            case "AVAILABLE" -> "disponible";
            case "UNAVAILABLE" -> "no disponible";
            case "STALE_OR_UNVERIFIED" -> "desactualizado o sin verificar";
            case "UNSUPPORTED" -> "no admitido";
            default -> formatted(parameter.getValue(), language);
          } : formatted(parameter.getValue(), language));
    return text;
  }

  /** A pointer-keyed sidecar preserves the original field types for existing report readers. */
  public static void annotate(ObjectNode payload, com.fasterxml.jackson.databind.ObjectMapper json) {
    var findings = json.createObjectNode();
    collect(payload.path("analysis"), "/analysis", findings, json);
    StructuredFindings.annotate(payload.path("analysis"), "/analysis", findings, json);
    payload.set("textFindings", findings);
  }

  public static ObjectNode annotateDesk(ObjectNode payload, com.fasterxml.jackson.databind.ObjectMapper json) {
    var findings = json.createObjectNode();
    collect(payload, "", findings, json);
    StructuredFindings.annotate(payload, "", findings, json);
    payload.set("textFindings", findings);
    return payload;
  }

  private static void collect(JsonNode node, String pointer, ObjectNode findings,
      com.fasterxml.jackson.databind.ObjectMapper json) {
    if (node.isObject()) {
      node.fields().forEachRemaining(field -> {
        if (field.getKey().equals("label") && node.path("paperworkLabel").asBoolean(false)) return;
        if (!java.util.Set.of("evidence", "observedEvidence", "expectedEvidence", "documentSource", "terms", "inputs").contains(field.getKey()))
          collect(field.getValue(), pointer + "/" + field.getKey().replace("~", "~0").replace("/", "~1"), findings, json);
      });
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) collect(node.get(i), pointer + "/" + i, findings, json);
    } else if (node.isTextual()) {
      String field = pointer.replaceFirst("/\\d+$", "");
      field = field.substring(field.lastIndexOf('/') + 1);
      if (!java.util.Set.of("label", "summary", "reason", "nextAction", "ratingBasis", "reviewLabel", "deskQuestion",
          "buyerAction", "effectBasis", "noDollarEffectReason", "paragraphs", "topReasons", "limitations", "note", "listingNote", "question", "scope", "purpose", "what", "kindWording", "name", "sourceNote", "notice", "notes", "operator").contains(field)) return;
      if (field.equals("name") && !pointer.matches(".*/(?:(?:dealerAddOns|addOns)/lines|(?:governmentFeeCheck|governmentFees)/fees)/\\d+/name")) return;
      if (field.equals("label") && !pointer.matches("(?:/analysis)?/(?:materialAmounts|tacticFindings)/\\d+/label")
          && !pointer.matches(".*/calculation/operands/\\d+/label")) return;
      Finding finding = identify(node.asText());
      if (pointer.matches("/analysis/materialAmounts/\\d+/label") && finding == null)
        finding = new Finding("AMOUNT_DOCUMENT", Map.of("label", node.asText()), node.asText());
      if (finding != null) findings.set(pointer, json.valueToTree(finding));
    }
  }

  /** A presentation copy only. The stored report and its fingerprints are never modified. */
  public static JsonNode localized(JsonNode payload, String language) {
    var copy = payload.deepCopy();
    payload.path("textFindings").fields().forEachRemaining(entry -> {
      var value = entry.getValue();
      Map<String, Object> parameters = new LinkedHashMap<>();
      value.path("parameters").fields().forEachRemaining(p -> parameters.put(p.getKey(),
          new com.fasterxml.jackson.databind.ObjectMapper().convertValue(p.getValue(), Object.class)));
      var finding = new Finding(value.path("code").asText(), parameters, value.path("text").asText());
      int slash = entry.getKey().lastIndexOf('/');
      var parent = copy.at(entry.getKey().substring(0, slash));
      String field = entry.getKey().substring(slash + 1).replace("~1", "/").replace("~0", "~");
      if (parent.isObject()) ((ObjectNode) parent).put(field, render(language, finding));
      else if (parent.isArray()) ((com.fasterxml.jackson.databind.node.ArrayNode) parent)
          .set(Integer.parseInt(field), com.fasterxml.jackson.databind.node.TextNode.valueOf(render(language, finding)));
    });
    return copy;
  }
}

package com.dealanalyzer.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProhibitedClaimsTest {
  @Test void everyLanguageNamesTheSameClaims() {
    assertThat(ProhibitedClaims.BY_LANGUAGE.get("es").keySet())
        .containsExactlyInAnyOrderElementsOf(ProhibitedClaims.PATTERNS.keySet());
  }

  @Test void spanishClaimsAreFoundInSpanishText() {
    for (String claim : List.of("Es seguro firmar este contrato.", "Ahorro garantizado.", "Le damos el mejor precio.",
        "La tasa más baja del mercado.", "Negociamos por usted.", "Actuamos en su nombre.", "Su agente autónomo.",
        "Contactamos a los concesionarios."))
      assertThat(ProhibitedClaims.contains(claim, "es")).as(claim).isTrue();
  }

  @Test void spanishTextIsAlsoCheckedForEnglishClaims() {
    assertThat(ProhibitedClaims.contains("Este trato es safe to sign.", "es")).isTrue();
  }

  @Test void ordinarySpanishFindingsPass() {
    for (String text : List.of("La garantía extendida es un producto opcional; puede pedir que la quiten.",
        "El cargo «DOC FEE» es la tarifa de documentación del concesionario.",
        "Pregunte al representante del concesionario qué incluye este cargo.",
        "Usted decide y envía cada correo."))
      assertThat(ProhibitedClaims.contains(text, "es")).as(text).isFalse();
  }

  @Test void englishTextIsCheckedWithTheEnglishList() {
    assertThat(ProhibitedClaims.contains("We negotiate for you.")).isTrue();
    assertThat(ProhibitedClaims.contains("Negociamos por usted.")).isFalse();
  }
}

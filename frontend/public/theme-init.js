// Applies a stored appearance and language choice before the first paint. Mirrors src/theme.ts and
// src/i18n/languageChoice.ts.
(function () {
  try {
    var choice = window.localStorage.getItem("deal-xray-theme");
    if (choice === "light" || choice === "dark") {
      document.documentElement.setAttribute("data-theme", choice);
    }
    var language = window.localStorage.getItem("otdog-language");
    if (language === "en" || language === "es") {
      document.documentElement.setAttribute("lang", language);
    }
  } catch (unavailable) {
    // Without storage the page follows the device theme, and the app sets the language as it starts.
  }
})();

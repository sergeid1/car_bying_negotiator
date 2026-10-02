// Applies a stored appearance choice before the first paint. Mirrors src/theme.ts.
(function () {
  try {
    var choice = window.localStorage.getItem("deal-xray-theme");
    if (choice === "light" || choice === "dark") {
      document.documentElement.setAttribute("data-theme", choice);
    }
  } catch (unavailable) {
    // Without storage the page follows the device theme.
  }
})();

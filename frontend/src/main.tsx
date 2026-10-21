import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
// Self-hosted so no font request leaves the site.
import "@fontsource-variable/geist";
import "@fontsource-variable/geist-mono";
import App from "./App";
import { LanguageProvider } from "./i18n/LanguageProvider";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <LanguageProvider>
      <App />
    </LanguageProvider>
  </StrictMode>,
);


import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
// Self-hosted so no font request leaves the site.
import "@fontsource-variable/geist";
import "@fontsource-variable/geist-mono";
import App from "./App";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);


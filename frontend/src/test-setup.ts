import "@testing-library/jest-dom/vitest";
import { configure } from "@testing-library/react";

// The default one-second wait for findBy*/waitFor is shorter than a loaded CI runner needs for
// multi-request flows; a longer ceiling costs nothing when the element appears promptly.
configure({ asyncUtilTimeout: 5000 });

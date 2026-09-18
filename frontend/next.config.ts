import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Emits .next/standalone with a self-contained server.js, so the runtime image
  // carries no build toolchain and no dev dependencies.
  output: "standalone",
};

export default nextConfig;

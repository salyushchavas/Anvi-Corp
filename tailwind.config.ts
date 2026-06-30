import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./app/**/*.{ts,tsx}",
    "./components/**/*.{ts,tsx}",
  ],
  theme: {
    container: {
      center: true,
      padding: { DEFAULT: "1rem", sm: "1.5rem", lg: "2rem" },
      screens: { sm: "640px", md: "768px", lg: "1024px", xl: "1200px" },
    },
    extend: {
      colors: {
        brand: {
          DEFAULT: "#2A8CDB",
          50:  "#EFF7FD",
          100: "#D8ECFA",
          200: "#B1D8F4",
          300: "#7BBDED",
          400: "#4FA3E1",
          500: "#2A8CDB",
          600: "#1F6BA8",
          700: "#185486",
          800: "#143F66",
          900: "#0E2A45",
        },
        accent: {
          DEFAULT: "#3C72FC",
          600: "#264FCB",
        },
        ink: {
          DEFAULT: "#0F0D1D",
          50:  "#F6F7F9",
          100: "#EAEBED",
          200: "#D2D4D8",
          300: "#A7AAB1",
          400: "#777181",
          500: "#55585B",
          600: "#33363A",
          700: "#1B1D22",
          800: "#0F0D1D",
          900: "#020B18",
        },
      },
      fontFamily: {
        sans: ["var(--font-kumbh)", "system-ui", "sans-serif"],
      },
      boxShadow: {
        card: "0 8px 32px -12px rgba(15, 13, 29, 0.10)",
        cardHover: "0 16px 48px -16px rgba(42, 140, 219, 0.22)",
      },
      backgroundImage: {
        "brand-gradient": "linear-gradient(135deg, #2A8CDB 0%, #3C72FC 100%)",
        "ink-gradient": "linear-gradient(180deg, #0F0D1D 0%, #020B18 100%)",
      },
      animation: {
        "float-slow": "float 8s ease-in-out infinite",
        "anvi-rise":  "anvi-rise 25s linear infinite",
      },
      keyframes: {
        float: {
          "0%, 100%": { transform: "translateY(0) scale(1)", opacity: "0.6" },
          "50%":      { transform: "translateY(-30px) scale(1.1)", opacity: "1" },
        },
        // ported from _legacy/assets/css/main.css @keyframes animate
        "anvi-rise": {
          "0%":   { transform: "translateY(0) rotate(0deg)",      opacity: "1", borderRadius: "0" },
          "100%": { transform: "translateY(-1000px) rotate(720deg)", opacity: "0", borderRadius: "50%" },
        },
      },
    },
  },
  plugins: [],
};
export default config;

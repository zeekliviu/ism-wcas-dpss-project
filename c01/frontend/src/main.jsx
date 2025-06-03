import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import "./index.css";
import Layout from "./components/layout/Layout.jsx";
import Home from "./pages/Home.jsx";
import Encrypt from "./pages/Encrypt.jsx";
import Gallery from "./pages/Gallery.jsx";
import About from "./pages/About.jsx";
import NotFound from "./pages/NotFound.jsx";
import Status from "./pages/Status.jsx";

createRoot(document.getElementById("root")).render(
  <StrictMode>
    <BrowserRouter>
      <Layout>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/encrypt" element={<Encrypt />} />
          <Route path="/gallery" element={<Gallery />} />
          <Route path="/status" element={<Status />} />
          <Route path="/about" element={<About />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </Layout>
    </BrowserRouter>
  </StrictMode>
);

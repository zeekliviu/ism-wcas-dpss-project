import { useState } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import {
  AppBar,
  Toolbar,
  Typography,
  Button,
  IconButton,
  Avatar,
  useMediaQuery,
  Menu,
  MenuItem,
  Box,
  Container,
  useTheme,
} from "@mui/material";
import MenuIcon from "@mui/icons-material/Menu";

const pages = [
  { name: "Acasă", path: "/" },
  { name: "(De)criptează", path: "/encrypt" },
  { name: "Galerie", path: "/gallery" },
  { name: "Stare", path: "/status" },
  { name: "Despre", path: "/about" },
];

const Layout = ({ children }) => {
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down("md"));
  const [anchorElNav, setAnchorElNav] = useState(null);
  const navigate = useNavigate();
  const location = useLocation();

  const handleOpenNavMenu = (event) => {
    setAnchorElNav(event.currentTarget);
  };

  const handleCloseNavMenu = () => {
    setAnchorElNav(null);
  };

  const handleNavigate = (path) => {
    navigate(path);
    handleCloseNavMenu();
  };

  const isActive = (path) => location.pathname === path;

  return (
    <Box
      sx={{
        display: "flex",
        flexDirection: "column",
        minHeight: "100vh",
        background: "linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%)",
      }}
    >
      <AppBar
        position="sticky"
        sx={{ background: "white", color: "black", boxShadow: 2 }}
      >
        <Container maxWidth="xl">
          <Toolbar disableGutters>
            {!isMobile && (
              <>
                <Typography
                  variant="h6"
                  noWrap
                  component="div"
                  sx={{
                    mr: 2,
                    display: "flex",
                    alignItems: "center",
                    cursor: "pointer",
                  }}
                  onClick={() => navigate("/")}
                >
                  <img
                    src="/satellite.svg"
                    alt="Logo"
                    style={{ height: "40px", marginRight: "10px" }}
                  />
                  (De)Cryptify
                </Typography>
                <Box sx={{ flexGrow: 1, display: "flex" }}>
                  {pages.map((page) => (
                    <Button
                      key={page.name}
                      onClick={() => handleNavigate(page.path)}
                      sx={{
                        my: 2,
                        color: isActive(page.path) ? "primary.main" : "black",
                        display: "block",
                        fontWeight: isActive(page.path) ? "bold" : "normal",
                      }}
                    >
                      {page.name}
                    </Button>
                  ))}
                </Box>
              </>
            )}

            {isMobile && (
              <>
                <IconButton
                  size="large"
                  aria-label="open navigation menu"
                  aria-controls="menu-appbar"
                  aria-haspopup="true"
                  onClick={handleOpenNavMenu}
                  color="inherit"
                  sx={{ mr: 2 }}
                >
                  <MenuIcon />
                </IconButton>
                <Menu
                  id="menu-appbar"
                  anchorEl={anchorElNav}
                  anchorOrigin={{
                    vertical: "bottom",
                    horizontal: "left",
                  }}
                  keepMounted
                  transformOrigin={{
                    vertical: "top",
                    horizontal: "left",
                  }}
                  open={Boolean(anchorElNav)}
                  onClose={handleCloseNavMenu}
                >
                  {pages.map((page) => (
                    <MenuItem
                      key={page.name}
                      onClick={() => handleNavigate(page.path)}
                      selected={isActive(page.path)}
                    >
                      <Typography textAlign="center">{page.name}</Typography>
                    </MenuItem>
                  ))}
                </Menu>
                <Typography
                  variant="h6"
                  noWrap
                  component="div"
                  sx={{
                    flexGrow: 1,
                    display: "flex",
                    justifyContent: "center",
                    alignItems: "center",
                    cursor: "pointer",
                  }}
                  onClick={() => navigate("/")}
                >
                  <img
                    src="/satellite.svg"
                    alt="Logo"
                    style={{ height: "40px", marginRight: "10px" }}
                  />
                  (De)Cryptify
                </Typography>
              </>
            )}

            <Avatar sx={{ bgcolor: "#1976d2", ml: 2 }}>U</Avatar>
          </Toolbar>
        </Container>
      </AppBar>

      <Box component="main" sx={{ flexGrow: 1, py: 4, px: 2 }}>
        <Container maxWidth="xl">{children}</Container>
      </Box>

      <Box
        component="footer"
        sx={{
          py: 3,
          px: 2,
          mt: "auto",
          backgroundColor: (theme) => theme.palette.grey[200],
          textAlign: "center",
        }}
      >
        <Typography variant="body2" color="text.secondary">
          © {new Date().getFullYear()} (De)Cryptify. Aplicație demonstrativă
          pentru ISM @ Web&Cloud + Distributed&Parallel Sys Security.
        </Typography>
      </Box>
    </Box>
  );
};

export default Layout;

import { Box, Typography, Paper, Button, Container } from "@mui/material";
import { useNavigate } from "react-router-dom";
import ErrorOutlineIcon from "@mui/icons-material/ErrorOutline";
import HomeIcon from "@mui/icons-material/Home";

const NotFound = () => {
  const navigate = useNavigate();

  return (
    <Container maxWidth="md">
      <Box
        sx={{
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
          minHeight: "60vh",
          textAlign: "center",
          py: 4,
        }}
      >
        <ErrorOutlineIcon sx={{ fontSize: 100, color: "grey.500", mb: 2 }} />

        <Typography
          variant="h2"
          component="h1"
          gutterBottom
          sx={{ fontWeight: "bold" }}
        >
          404
        </Typography>

        <Typography variant="h4" component="h2" gutterBottom>
          Pagină negăsită
        </Typography>

        <Paper
          elevation={3}
          sx={{ p: 4, mt: 2, mb: 4, width: "100%", maxWidth: 600 }}
        >
          <Typography variant="body1" paragraph>
            Ne pare rău, dar pagina pe care încercați să o accesați nu există
            sau a fost mutată.
          </Typography>
          <Typography variant="body1" sx={{ mb: 3 }}>
            Verificați URL-ul pentru greșeli sau navigați înapoi la pagina
            principală.
          </Typography>

          <Button
            variant="contained"
            color="primary"
            size="large"
            startIcon={<HomeIcon />}
            onClick={() => navigate("/")}
            sx={{ mt: 2 }}
          >
            Înapoi la pagina principală
          </Button>
        </Paper>
      </Box>
    </Container>
  );
};

export default NotFound;

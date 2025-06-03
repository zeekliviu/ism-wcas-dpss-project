import { Box, Typography, Paper, Container, Grid } from "@mui/material";

const Home = () => {
  return (
    <Container
      maxWidth="lg"
      sx={{
        display: "flex",
        flexDirection: "column",
        alignItems: "stretch",
      }}
    >
      <Box sx={{ my: 4, width: "100%" }}>
        <Typography variant="h3" component="h1" gutterBottom textAlign="center">
          Bine ați venit la (De)Cryptify
        </Typography>
        <Typography
          variant="h5"
          component="h2"
          gutterBottom
          textAlign="center"
          color="text.secondary"
        >
          Aplicație demonstrativă pentru criptarea și decriptarea imaginilor
          mari
        </Typography>

        <Grid
          container
          spacing={3}
          sx={{
            mt: 4,
            justifyContent: "center",
            alignItems: "stretch",
          }}
        >
          <Grid item xs={12} md={6} sx={{ display: "flex" }}>
            <Paper
              elevation={3}
              sx={{
                p: 3,
                width: "100%",
                display: "flex",
                flexDirection: "column",
                justifyContent: "flex-start",
              }}
            >
              <Typography variant="h5" component="h2" gutterBottom>
                Ce este (De)Cryptify?
              </Typography>
              <Typography variant="body1" paragraph>
                (De)Cryptify este o aplicație ce permite criptarea și
                decriptarea imaginilor mari în mod paralel și distribuit.
              </Typography>
            </Paper>
          </Grid>

          <Grid item xs={12} md={6} sx={{ display: "flex" }}>
            <Paper
              elevation={3}
              sx={{
                p: 3,
                width: "100%",
                display: "flex",
                flexDirection: "column",
                justifyContent: "flex-start",
              }}
            >
              <Typography variant="h5" component="h2" gutterBottom>
                Funcționalități principale
              </Typography>
              <Typography variant="body1" component="ul" sx={{ pl: 2 }}>
                <li>Criptare și decriptare de imagini</li>
                <li>Selectarea modului de criptare (AES CBC/ECB)</li>
                <li>Galeria tuturor joburilor finalizate</li>
                <li>
                  Vizualizarea stării aplicației (informații despre containere)
                </li>
              </Typography>
            </Paper>
          </Grid>
        </Grid>
      </Box>
    </Container>
  );
};

export default Home;

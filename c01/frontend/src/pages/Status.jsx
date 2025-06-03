import { useState, useEffect } from "react";
import {
  Paper,
  Typography,
  Box,
  LinearProgress,
  Divider,
  Container,
  Grid,
  Card,
  CardContent,
  CircularProgress,
  Alert,
  Chip,
} from "@mui/material";
import MemoryIcon from "@mui/icons-material/Memory";
import StorageIcon from "@mui/icons-material/Storage";
import ComputerIcon from "@mui/icons-material/Computer";
import {
  Computer,
  Timeline,
  Memory,
  CheckCircle,
  Error,
  Warning,
} from "@mui/icons-material";

export default function Status() {
  const [dashboardData, setDashboardData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const apiBaseUrl =
    import.meta.env.VITE_API_BASE_URL || "http://localhost:3000";

  useEffect(() => {
    const fetchMetrics = async () => {
      try {
        const response = await fetch(`${apiBaseUrl}/api/snmp/dashboard`);
        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }
        const data = await response.json();
        setDashboardData(data);
        setError(null);
      } catch (err) {
        console.error("Failed to fetch metrics:", err);
        setError("Failed to load metrics data");
      } finally {
        setLoading(false);
      }
    };

    fetchMetrics();

    const interval = setInterval(fetchMetrics, 30000);

    return () => clearInterval(interval);
  }, []);

  const getColorForUsage = (value) => {
    if (value < 40) return "success";
    if (value < 70) return "warning";
    return "error";
  };

  const getStatusColor = (status) => {
    switch (status) {
      case "online":
        return "success";
      case "offline":
        return "error";
      case "no-data":
        return "warning";
      default:
        return "default";
    }
  };

  const getStatusIcon = (status) => {
    switch (status) {
      case "online":
        return <CheckCircle />;
      case "offline":
        return <Error />;
      case "no-data":
        return <Warning />;
      default:
        return null;
    }
  };

  const formatTimestamp = (timestamp) => {
    if (!timestamp) return "N/A";
    return new Date(timestamp).toLocaleString();
  };

  if (loading) {
    return (
      <Container maxWidth="lg">
        <Box
          sx={{
            display: "flex",
            justifyContent: "center",
            alignItems: "center",
            height: "50vh",
          }}
        >
          <CircularProgress />
        </Box>
      </Container>
    );
  }

  if (error) {
    return (
      <Container maxWidth="lg" sx={{ mt: 4 }}>
        <Typography variant="h4" component="h1" gutterBottom align="center">
          Panou monitorizare SNMP
        </Typography>
        <Box sx={{ my: 4 }}>
          <Alert severity="error">{error}</Alert>
        </Box>
      </Container>
    );
  }
  return (
    <Container maxWidth="lg" sx={{ mt: 4 }}>
      <Typography variant="h4" component="h1" gutterBottom align="center">
        Panou monitorizare SNMP
      </Typography>
      <Typography
        variant="subtitle1"
        gutterBottom
        align="center"
        color="text.secondary"
      >
        Monitorizare în timp real a stării containerelor
      </Typography>

      {dashboardData?.summary && (
        <Grid container spacing={3} sx={{ mb: 4, mt: 2 }}>
          <Grid item xs={12} sm={6} md={3}>
            <Card>
              <CardContent>
                <Box sx={{ display: "flex", alignItems: "center", mb: 1 }}>
                  <Computer sx={{ mr: 1 }} />
                  <Typography variant="h6">Containere</Typography>
                </Box>
                <Typography variant="h4">
                  {dashboardData.summary.activeContainers}/
                  {dashboardData.summary.totalContainers}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  active/total
                </Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <Card>
              <CardContent>
                <Box sx={{ display: "flex", alignItems: "center", mb: 1 }}>
                  <Timeline sx={{ mr: 1 }} />
                  <Typography variant="h6">Avg% CPU</Typography>
                </Box>
                <Typography variant="h4">
                  {dashboardData.summary.avgCpuUsage}%
                </Typography>
                <LinearProgress
                  variant="determinate"
                  value={dashboardData.summary.avgCpuUsage}
                  color={getColorForUsage(dashboardData.summary.avgCpuUsage)}
                  sx={{ mt: 1, height: 6, borderRadius: 3 }}
                />
              </CardContent>
            </Card>
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <Card>
              <CardContent>
                <Box sx={{ display: "flex", alignItems: "center", mb: 1 }}>
                  <Memory sx={{ mr: 1 }} />
                  <Typography variant="h6">Avg% RAM</Typography>
                </Box>
                <Typography variant="h4">
                  {dashboardData.summary.avgRamUsage}%
                </Typography>
                <LinearProgress
                  variant="determinate"
                  value={dashboardData.summary.avgRamUsage}
                  color={getColorForUsage(dashboardData.summary.avgRamUsage)}
                  sx={{ mt: 1, height: 6, borderRadius: 3 }}
                />
              </CardContent>
            </Card>
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <Card>
              <CardContent>
                <Typography variant="h6" gutterBottom>
                  Ultima actualizare
                </Typography>
                <Typography variant="body2">
                  {formatTimestamp(dashboardData.summary.lastUpdated)}
                </Typography>
              </CardContent>
            </Card>
          </Grid>
        </Grid>
      )}

      <Box
        sx={{
          display: "flex",
          flexDirection: "row",
          flexWrap: "wrap",
          gap: 3,
          mt: 3,
          justifyContent: "center",
        }}
      >
        {Object.entries(dashboardData?.containers || {}).map(
          ([containerId, container]) => (
            <Box
              key={containerId}
              sx={{
                width: {
                  xs: "100%",
                  sm: "calc(50% - 16px)",
                  lg: "calc(33.33% - 16px)",
                },
                display: "flex",
              }}
            >
              <Paper
                elevation={3}
                sx={{
                  p: 3,
                  borderRadius: 2,
                  width: "100%",
                  minHeight: 280,
                  display: "flex",
                  flexDirection: "column",
                  transition: "transform 0.2s",
                  "&:hover": {
                    transform: "translateY(-5px)",
                    boxShadow: 6,
                  },
                }}
              >
                <Box
                  sx={{
                    display: "flex",
                    justifyContent: "space-between",
                    alignItems: "center",
                    mb: 2,
                  }}
                >
                  <Typography variant="h5" component="h2">
                    {containerId} (hostname: {container.hostname})
                  </Typography>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                    <Chip
                      icon={getStatusIcon(container.status)}
                      label={container.status}
                      color={getStatusColor(container.status)}
                      size="small"
                    />
                  </Box>
                </Box>

                <Divider sx={{ mb: 2 }} />

                <Box sx={{ display: "flex", alignItems: "center", mb: 2 }}>
                  <ComputerIcon sx={{ mr: 1, flexShrink: 0 }} />
                  <Typography
                    variant="body1"
                    noWrap
                    sx={{
                      width: "100%",
                      overflow: "hidden",
                      textOverflow: "ellipsis",
                    }}
                  >
                    OS: {container.osName || "N/A"}
                  </Typography>
                </Box>

                {container.status === "online" ||
                container.status === "offline" ? (
                  <Box sx={{ mt: "auto", width: "100%" }}>
                    <Box sx={{ mb: 2, width: "100%" }}>
                      <Box
                        sx={{ display: "flex", alignItems: "center", mb: 1 }}
                      >
                        <MemoryIcon sx={{ mr: 1, flexShrink: 0 }} />
                        <Typography variant="body2">
                          Utilizare CPU: {Math.round(container.cpuUsage || 0)}%
                        </Typography>
                      </Box>
                      <LinearProgress
                        variant="determinate"
                        value={container.cpuUsage || 0}
                        color={getColorForUsage(container.cpuUsage || 0)}
                        sx={{ height: 8, borderRadius: 4, width: "100%" }}
                      />
                    </Box>

                    <Box sx={{ width: "100%" }}>
                      <Box
                        sx={{ display: "flex", alignItems: "center", mb: 1 }}
                      >
                        <StorageIcon sx={{ mr: 1, flexShrink: 0 }} />
                        <Typography variant="body2">
                          Utilizare RAM: {Math.round(container.ramUsage || 0)}%
                        </Typography>
                      </Box>
                      <LinearProgress
                        variant="determinate"
                        value={container.ramUsage || 0}
                        color={getColorForUsage(container.ramUsage || 0)}
                        sx={{ height: 8, borderRadius: 4, width: "100%" }}
                      />
                    </Box>

                    <Typography
                      variant="caption"
                      color="text.secondary"
                      sx={{ mt: 2, display: "block" }}
                    >
                      Ultima verificare: {formatTimestamp(container.lastSeen)}
                    </Typography>
                  </Box>
                ) : (
                  <Box sx={{ mt: "auto", width: "100%", textAlign: "center" }}>
                    <Typography variant="body2" color="text.secondary">
                      Date indisponibile momentan.
                    </Typography>
                  </Box>
                )}
              </Paper>
            </Box>
          )
        )}
      </Box>

      {/* Information */}
      <Box sx={{ mt: 4 }}>
        <Alert severity="info">
          Statisticile containerelor sunt colectate la fiecare 30 de secunde și
          stocate în MongoDB. Acest panou informativ se modifică automat.
        </Alert>
      </Box>
    </Container>
  );
}

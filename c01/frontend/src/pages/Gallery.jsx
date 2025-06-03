import {
  CircularProgress,
  Box,
  IconButton,
  Tooltip,
  Typography,
} from "@mui/material";
import { useMemo, useState, useEffect, useRef } from "react";
import { MaterialReactTable } from "material-react-table";
import { MRT_Localization_RO } from "material-react-table/locales/ro";
import LockIcon from "@mui/icons-material/Lock";
import LockOpenOutlinedIcon from "@mui/icons-material/LockOpenOutlined";
import CheckCircleIcon from "@mui/icons-material/CheckCircle";
import ErrorIcon from "@mui/icons-material/Error";
import HourglassEmptyIcon from "@mui/icons-material/HourglassEmpty";
import DownloadIcon from "@mui/icons-material/Download";

export default function Gallery() {
  const [jobs, setJobs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const apiBaseUrl =
    import.meta.env.VITE_API_BASE_URL || "http://localhost:3000";
  const wsBaseUrl = import.meta.env.VITE_WS_BASE_URL || "ws://localhost:8080";

  const jobWebSockets = useRef({});

  useEffect(() => {
    const fetchInitialData = async () => {
      setLoading(true);
      try {
        const response = await fetch(`${apiBaseUrl}/api/jobs`);
        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }
        const initialJobs = await response.json();
        setJobs(initialJobs);
        setError(null);

        initialJobs.forEach((job) => {
          if (job.status !== "DONE" && job.status !== "ERROR") {
            setupWebSocketForJob(job.jobId);
          }
        });
      } catch (e) {
        console.error("Failed to fetch initial jobs:", e);
        setError(e.message);
      } finally {
        setLoading(false);
      }
    };

    fetchInitialData();

    return () => {
      Object.values(jobWebSockets.current).forEach((ws) => {
        if (ws && ws.readyState === WebSocket.OPEN) {
          console.log("Closing WebSocket on component unmount:", ws.url);
          ws.close();
        }
      });
      jobWebSockets.current = {};
    };
  }, [apiBaseUrl, wsBaseUrl]);

  const setupWebSocketForJob = (jobId) => {
    if (
      jobWebSockets.current[jobId] &&
      jobWebSockets.current[jobId].readyState !== WebSocket.CLOSED
    ) {
      return;
    }

    const wsUrl = `${wsBaseUrl}/api/jobstatus/${jobId}`;
    const ws = new WebSocket(wsUrl);
    jobWebSockets.current[jobId] = ws;

    ws.onopen = () => {
      console.log(
        `[Gallery.jsx] WebSocket connected for job ${jobId} at ${wsUrl}`
      );
    };

    ws.onmessage = (event) => {
      try {
        const messageData = JSON.parse(event.data);
        console.log(
          `[Gallery.jsx] WebSocket message received for Job ID (from WS message): ${messageData.jobId}. Parsed data:`,
          JSON.stringify(messageData, null, 2)
        );

        setJobs((prevJobs) => {
          console.log(
            `[Gallery.jsx] setJobs: Updating jobs. Current jobs count: ${prevJobs.length}. Message Job ID (from WS data): ${messageData.jobId}`
          );
          let jobFound = false;
          const updatedJobs = prevJobs.map((job) => {
            if (job.jobId === messageData.jobId) {
              jobFound = true;
              return {
                ...job,
                status: messageData.status,
                errorMessage:
                  messageData.errorMessage !== undefined
                    ? messageData.errorMessage
                    : job.errorMessage,
                downloadLink:
                  messageData.status === "DONE" && messageData.downloadLink
                    ? messageData.downloadLink
                    : messageData.status === "ERROR"
                    ? null
                    : job.downloadLink,
                finished_at:
                  messageData.status === "DONE" && messageData.finished_at
                    ? messageData.finished_at
                    : job.finished_at,
              };
            }
            return job;
          });

          if (!jobFound) {
            console.warn(
              `[Gallery.jsx] setJobs: Job with ID ${messageData.jobId} not found in current jobs list. WS message might be for a new job not yet fetched or an old/stale one. Current jobs:`,
              prevJobs.map((j) => j.jobId)
            );
            return prevJobs;
          } else {
            console.log(
              `[Gallery.jsx] setJobs: Job ${messageData.jobId} updated in the new jobs array. New status: ${messageData.status}`
            );
          }
          return updatedJobs;
        });

        if (messageData.status === "DONE" || messageData.status === "ERROR") {
          console.log(
            `[Gallery.jsx] Job ${messageData.jobId} reported as ${messageData.status}. Closing WebSocket.`
          );
          ws.close();
          if (jobWebSockets.current[messageData.jobId]) {
            delete jobWebSockets.current[messageData.jobId];
          }
        }
      } catch (parseError) {
        console.error(
          `[Gallery.jsx] Failed to parse WebSocket message for job ${jobId}:`,
          parseError,
          "Raw Data:",
          event.data
        );
      }
    };

    ws.onerror = (error) => {
      console.error(`WebSocket error for job ${jobId}:`, error);
    };

    ws.onclose = (event) => {
      console.log(
        `WebSocket disconnected for job ${jobId}. Code: ${event.code}, Reason: ${event.reason}`
      );
      if (jobWebSockets.current[jobId]) {
        delete jobWebSockets.current[jobId];
      }
    };
  };

  useEffect(() => {
    jobs.forEach((job) => {
      if (
        job.status !== "DONE" &&
        job.status !== "ERROR" &&
        !jobWebSockets.current[job.jobId]
      ) {
        console.log(
          `Setting up WebSocket for existing job not in final state: ${job.jobId}`
        );
        setupWebSocketForJob(job.jobId);
      }
    });
  }, [jobs]);

  const handleDownload = async (downloadUrl, originalFileName) => {
    try {
      const response = await fetch(downloadUrl);
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }
      const blob = await response.blob();
      const objectUrl = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = objectUrl;

      let filename = "download.bmp";
      if (originalFileName) {
        const namePart =
          originalFileName.lastIndexOf(".") !== -1
            ? originalFileName.substring(0, originalFileName.lastIndexOf("."))
            : originalFileName;
        filename = `${namePart}_processed.bmp`;
      } else {
        filename = `processed_image_${Date.now()}.bmp`;
      }

      link.setAttribute("download", filename);
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(objectUrl);
    } catch (e) {
      console.error("Download failed:", e);
      alert(`Failed to download file: ${e.message}`);
    }
  };

  const columns = useMemo(
    () => [
      {
        accessorKey: "jobId",
        header: "ID Job",
        size: 150,
      },
      {
        accessorKey: "createdAt",
        header: "Data Inițiere",
        Cell: ({ cell }) => new Date(cell.getValue()).toLocaleString(),
        size: 200,
      },
      {
        accessorKey: "type",
        header: "Tip",
        Cell: ({ cell }) => {
          const typeValue = cell.getValue();
          return (
            <Box sx={{ display: "flex", alignItems: "center" }}>
              {typeValue === "CRIPTARE" ? (
                <LockIcon color="primary" sx={{ mr: 1 }} />
              ) : typeValue === "DECRIPTARE" ? (
                <LockOpenOutlinedIcon color="secondary" sx={{ mr: 1 }} />
              ) : null}
              {typeValue}
            </Box>
          );
        },
        size: 150,
      },
      {
        accessorKey: "originalFileName",
        header: "Nume Fișier Original",
        size: 250,
      },
      {
        accessorKey: "status",
        header: "Status",
        Cell: ({ cell }) => {
          const statusValue = cell.getValue();
          let icon;
          let statusText = statusValue;
          switch (statusValue) {
            case "DONE":
              icon = <CheckCircleIcon color="success" sx={{ mr: 1 }} />;
              statusText = "Finalizat";
              break;
            case "ERROR":
              icon = <ErrorIcon color="error" sx={{ mr: 1 }} />;
              statusText = "Eroare";
              break;
            case "RUNNING":
              icon = <HourglassEmptyIcon sx={{ color: "orange", mr: 1 }} />;
              statusText = "În curs";
              break;
            case "QUEUED":
              icon = <HourglassEmptyIcon sx={{ color: "grey", mr: 1 }} />;
              statusText = "În așteptare";
              break;
            default:
              icon = <HourglassEmptyIcon sx={{ color: "action", mr: 1 }} />;
          }
          return (
            <Box sx={{ display: "flex", alignItems: "center" }}>
              {icon}
              {statusText}
            </Box>
          );
        },
        size: 150,
      },
      {
        accessorKey: "downloadLink",
        header: "Descărcare",
        Cell: ({ cell, row }) => {
          const relativeLink = cell.getValue();
          const isFinished = row.original.status === "DONE";
          const fullLink = relativeLink ? `${apiBaseUrl}${relativeLink}` : null;

          return (
            <Tooltip
              title={
                isFinished && relativeLink
                  ? "Descarcă fișierul"
                  : isFinished && !relativeLink
                  ? "Link de descărcare indisponibil"
                  : "Procesarea nu este finalizată"
              }
            >
              <span>
                <IconButton
                  onClick={() => {
                    if (isFinished && fullLink) {
                      handleDownload(fullLink, row.original.originalFileName);
                    }
                  }}
                  disabled={!isFinished || !relativeLink}
                  color={isFinished && relativeLink ? "primary" : "default"}
                >
                  <DownloadIcon />
                </IconButton>
              </span>
            </Tooltip>
          );
        },
        size: 150,
      },
      {
        accessorKey: "errorMessage",
        header: "Mesaj Eroare",
        size: 200,
        Cell: ({ cell }) => (
          <Tooltip title={cell.getValue() || "N/A"}>
            <Typography
              noWrap
              sx={{
                maxWidth: "200px",
                overflow: "hidden",
                textOverflow: "ellipsis",
              }}
            >
              {cell.getValue() || "-"}
            </Typography>
          </Tooltip>
        ),
      },
    ],
    [apiBaseUrl]
  );

  if (loading) {
    return (
      <Box
        display="flex"
        justifyContent="center"
        alignItems="center"
        height="80vh"
      >
        <CircularProgress />
        <Typography sx={{ ml: 2 }}>Se încarcă joburile...</Typography>
      </Box>
    );
  }

  if (error) {
    return (
      <Box
        display="flex"
        flexDirection="column"
        alignItems="center"
        justifyContent="center"
        height="80vh"
      >
        <ErrorIcon color="error" sx={{ fontSize: 40, mb: 2 }} />
        <Typography color="error">
          A apărut o eroare la încărcarea joburilor: {error}
        </Typography>
        <Typography>Vă rugăm încercați din nou mai târziu.</Typography>
      </Box>
    );
  }

  return (
    <Box
      display={"flex"}
      flexDirection={"column"}
      gap={2}
      sx={{ p: 2, width: "100%" }}
    >
      <Typography
        variant="h4"
        component="h1"
        gutterBottom
        alignSelf="center"
        sx={{ mb: 1 }}
      >
        Galerie
      </Typography>
      <Typography
        variant="h6"
        component="h2"
        gutterBottom
        alignSelf="center"
        sx={{ mb: 2 }}
      >
        Vizualizați și descărcați fișierele criptate și decriptate
      </Typography>

      <MaterialReactTable
        columns={columns}
        data={jobs}
        localization={MRT_Localization_RO}
        muiTablePaperProps={{
          elevation: 2,
          sx: {
            borderRadius: "8px",
          },
        }}
        muiTableContainerProps={{
          sx: { maxHeight: "calc(100vh - 250px)" },
        }}
        enableRowSelection={false}
        enableColumnOrdering
        enableGlobalFilter
        enableFullScreenToggle={false}
        enableDensityToggle={false}
        enableHiding={false}
        muiCircularProgressProps={{
          sx: { display: "none" },
        }}
        muiLinearProgressProps={{
          sx: { display: "none" },
        }}
        initialState={{ density: "compact" }}
      />
    </Box>
  );
}

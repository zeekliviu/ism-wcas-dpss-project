import { useState, useEffect } from "react";
import {
  Box,
  Typography,
  Paper,
  TextField,
  Button,
  FormControl,
  Select,
  MenuItem,
  Divider,
  Switch,
  Snackbar,
  Alert,
  CircularProgress,
} from "@mui/material";
import LockIcon from "@mui/icons-material/Lock";
import LockOpenOutlinedIcon from "@mui/icons-material/LockOpenOutlined";

const HEX_REGEX = /^[0-9a-fA-F]*$/;

export default function Encrypt() {
  const [image, setImage] = useState(null);
  const [option, setOption] = useState("encrypt");
  const [keySize, setKeySize] = useState(128);
  const [key, setKey] = useState("");
  const [iv, setIv] = useState("");
  const [algorithm, setAlgorithm] = useState("CBC");

  const [loading, setLoading] = useState(false);
  const [snackbar, setSnackbar] = useState({
    open: false,
    message: "",
    severity: "success",
  });
  const [imageError, setImageError] = useState("");
  const [keyError, setKeyError] = useState("");
  const [ivError, setIvError] = useState("");

  const modesThatSupportIV = ["CBC", "CFB", "OFB", "CTR", "GCM", "CCM", "SIV"];
  const needsIV = modesThatSupportIV.includes(algorithm);

  useEffect(() => {
    setKey("");
    setKeyError("");
  }, [keySize]);

  const validateImage = () => {
    if (!image) {
      setImageError("Imaginea este obligatorie.");
      return false;
    }
    if (image.type !== "image/bmp") {
      setImageError("Format invalid. Doar fișierele BMP (.bmp) sunt permise.");
      return false;
    }
    setImageError("");
    return true;
  };

  const validateKey = () => {
    if (!key) {
      setKeyError("Cheia este obligatorie.");
      return false;
    }
    if (!HEX_REGEX.test(key)) {
      setKeyError("Cheia trebuie să conțină doar caractere hexazecimale.");
      return false;
    }
    if (key.length !== keySize / 4) {
      setKeyError(`Lungimea cheii trebuie să fie de ${keySize / 4} caractere.`);
      return false;
    }
    setKeyError("");
    return true;
  };

  const validateIv = () => {
    if (!needsIV) {
      setIvError("");
      return true;
    }
    if (!iv) {
      setIvError("IV este obligatoriu pentru modul selectat.");
      return false;
    }
    if (!HEX_REGEX.test(iv)) {
      setIvError("IV trebuie să conțină doar caractere hexazecimale.");
      return false;
    }
    if (iv.length !== 32) {
      setIvError("Lungimea IV trebuie să fie de 32 de caractere.");
      return false;
    }
    setIvError("");
    return true;
  };

  const handleImageChange = (e) => {
    const file = e.target.files[0];
    setImage(file);
    if (file) {
      if (file.type !== "image/bmp") {
        setImageError(
          "Format invalid. Doar fișierele BMP (.bmp) sunt permise."
        );
      } else {
        setImageError("");
      }
    } else {
      setImageError("Imaginea este obligatorie.");
    }
  };

  const handleKeyChange = (e) => {
    const value = e.target.value;
    if (HEX_REGEX.test(value) || value === "") {
      setKey(value);
      if (keyError) setKeyError("");
    }
  };

  const handleIvChange = (e) => {
    const value = e.target.value;
    if (HEX_REGEX.test(value) || value === "") {
      setIv(value);
      if (ivError) setIvError("");
    }
  };

  const handleSnackbarClose = (event, reason) => {
    if (reason === "clickaway") {
      return;
    }
    setSnackbar({ ...snackbar, open: false });
  };

  const handleSubmit = async () => {
    const isImageValid = validateImage();
    const isKeyValid = validateKey();
    const isIvValid = validateIv();

    if (!isImageValid || !isKeyValid || !isIvValid) {
      return;
    }

    setLoading(true);
    const formData = new FormData();
    formData.append("fileName", image.name);
    formData.append("keySize", keySize);
    formData.append("iv", needsIV ? iv : "");
    formData.append("algorithm", "AES");
    formData.append("mode", algorithm);
    formData.append("key", key);
    formData.append("file", image);
    formData.append("operation", option);

    try {
      const response = await fetch("/api/newJob", {
        method: "POST",
        body: formData,
      });
      const message = await response.text();
      setSnackbar({
        open: true,
        message:
          message || (response.ok ? "Operație reușită!" : "A apărut o eroare."),
        severity: response.ok ? "success" : "error",
      });
    } catch (error) {
      console.error("API call error:", error);
      setSnackbar({
        open: true,
        message: "Eroare de rețea sau server indisponibil.",
        severity: "error",
      });
    } finally {
      setLoading(false);
    }
  };

  return (
    <Paper
      elevation={3}
      sx={{
        p: 3,
        width: "100%",
        display: "flex",
        flexDirection: "column",
        justifyContent: "flex-start",
        alignItems: "center",
      }}
    >
      <Box sx={{ mb: 2, display: "flex", alignItems: "center", gap: 1 }}>
        <Typography variant="h5" component="h2" gutterBottom>
          {option === "encrypt" ? "Criptează " : "Decriptează "}imaginea
        </Typography>
        <Switch
          checked={option === "encrypt"}
          icon={<LockOpenOutlinedIcon color="warning" />}
          checkedIcon={<LockIcon />}
          onChange={() =>
            setOption((prev) => (prev === "encrypt" ? "decrypt" : "encrypt"))
          }
        />
      </Box>
      <Divider sx={{ width: "100%", mb: 2 }} />
      <Box
        sx={{
          width: "100%",
          mb: 2,
          display: "flex",
          gap: 2,
          flexDirection: "column",
        }}
      >
        <Box sx={{ display: "flex", gap: 2, alignItems: "center" }}>
          <Typography minWidth={100}>
            {option === "encrypt" ? "Imagine" : "Imagine criptată"}
          </Typography>
          <TextField
            type="file"
            accept="image/bmp, .bmp"
            onChange={handleImageChange}
            fullWidth
            variant="filled"
            error={!!imageError}
            helperText={imageError}
            onBlur={validateImage}
          />
        </Box>
        <Box sx={{ display: "flex", gap: 2, alignItems: "center" }}>
          <Typography minWidth={100}>Dimensiune cheie</Typography>
          <FormControl fullWidth variant="outlined">
            <Select
              labelId="key-size-select-label"
              id="key-size-select"
              value={keySize}
              onChange={(e) => {
                setKeySize(e.target.value);
              }}
            >
              <MenuItem value={128}>128</MenuItem>
              <MenuItem value={192}>192</MenuItem>
              <MenuItem value={256}>256</MenuItem>
            </Select>
          </FormControl>
        </Box>
        <Box sx={{ display: "flex", gap: 2, alignItems: "center" }}>
          <Typography minWidth={100}>
            {option === "encrypt" ? "Cheie" : "Cheie de decriptare"}
          </Typography>
          <TextField
            type="text"
            placeholder="Introduceți cheia"
            fullWidth
            variant="outlined"
            value={key}
            onChange={handleKeyChange}
            onBlur={validateKey}
            error={!!keyError}
            helperText={
              keyError ||
              `Cheia trebuie să fie în lungime de ${
                keySize / 4
              } caractere hexazecimale.`
            }
            slotProps={{ htmlInput: { maxLength: keySize / 4 } }}
          />
        </Box>
        <Box sx={{ display: "flex", gap: 2, alignItems: "center" }}>
          <Typography minWidth={100}>Mod</Typography>
          <FormControl fullWidth variant="outlined">
            <Select
              labelId="algorithm-select-label"
              id="algorithm-select"
              value={algorithm}
              onChange={(e) => setAlgorithm(e.target.value)}
            >
              <MenuItem value="CBC">CBC</MenuItem>
              <MenuItem value="ECB">ECB</MenuItem>
            </Select>
          </FormControl>
        </Box>
        {needsIV && (
          <Box sx={{ display: "flex", gap: 2, alignItems: "center" }}>
            <Typography minWidth={100}>IV</Typography>
            <TextField
              type="text"
              placeholder="Introduceți IV"
              fullWidth
              variant="outlined"
              value={iv}
              onChange={handleIvChange}
              onBlur={validateIv}
              error={!!ivError}
              helperText={
                ivError ||
                "IV trebuie să fie în lungime de 32 de caractere hexazecimale."
              }
              slotProps={{ htmlInput: { maxLength: 32 } }}
            />
          </Box>
        )}
        <Button
          variant="contained"
          color="primary"
          size="large"
          endIcon={
            loading ? (
              <CircularProgress size={24} color="inherit" />
            ) : option === "encrypt" ? (
              <LockIcon />
            ) : (
              <LockOpenOutlinedIcon />
            )
          }
          sx={{ mt: 2 }}
          onClick={handleSubmit}
          disabled={loading}
        >
          {option === "encrypt" ? "Criptează" : "Decriptează"}
        </Button>
      </Box>
      <Snackbar
        open={snackbar.open}
        autoHideDuration={6000}
        onClose={handleSnackbarClose}
        anchorOrigin={{ vertical: "bottom", horizontal: "center" }}
      >
        <Alert
          onClose={handleSnackbarClose}
          severity={snackbar.severity}
          sx={{ width: "100%" }}
          variant="filled"
        >
          {snackbar.message}
        </Alert>
      </Snackbar>
    </Paper>
  );
}

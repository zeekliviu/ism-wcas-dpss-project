import {
  Box,
  Typography,
  Paper,
  Container,
  Divider,
  List,
  ListItem,
  ListItemIcon,
  ListItemText,
  Avatar,
  IconButton,
  Stack,
  Link,
} from "@mui/material";
import EmailIcon from "@mui/icons-material/Email";
import LinkedInIcon from "@mui/icons-material/LinkedIn";
import GitHubIcon from "@mui/icons-material/GitHub";
import { FaDiscord, FaJava, FaReact, FaDocker, FaLinux } from "react-icons/fa";
import {
  SiCplusplus,
  SiRabbitmq,
  SiMysql,
  SiMongodb,
  SiExpress,
} from "react-icons/si";

const About = () => {
  const author = {
    name: "Liviu-Ioan ZECHERU (Zeek)",
    role: "Full Stack Developer @ ASE București",
    description:
      "Sunt o forță de nestăvilit. Aș face matematică, informatică, fiolosofie, literatură și sex la nesfârșit.",
    email: "zecheruliviu21@stud.ase.ro",
    linkedin: "https://www.linkedin.com/in/zeekliviu",
    github: "https://github.com/zeekliviu",
    discord: "zeekliviu",
  };

  return (
    <Container maxWidth="lg">
      <Box sx={{ my: 4 }}>
        <Typography variant="h4" component="h1" gutterBottom textAlign="center">
          Despre (De)Cryptify
        </Typography>

        <Paper elevation={3} sx={{ p: 4, mt: 4 }}>
          <Typography variant="h5" component="h2" gutterBottom>
            Proiect
          </Typography>
          <Typography variant="body1" paragraph textAlign={"justify"}>
            (De)Cryptify este un PoC (Proof of Concept) pentru o aplicație web
            care demonstrează criptarea și decriptarea AES ECB/CBC a imaginilor
            BMP de mari dimensiuni. Datele inițiale (imaginea, cheia simetrică
            pe 128/192/256 de biți, cât și IV-ul pentru modul CBC) sunt primite
            de la frontend (React) printr-un API RESTful implementat în Javalin
            (C01). Aceste date sunt apoi publicate ca mesaj binar într-un topic
            RabbitMQ (C02). Un consumator Java POJO (în C03) se abonează la
            acest topic și lansează un proces nativ C++ (executabil ELF64).
            Acest proces nativ utilizează OpenMPI pentru distribuirea imaginii
            și OpenMP pentru procesarea paralelă a criptării/decriptării pe
            porțiuni de imagine (în C03 și C04), folosind OpenSSL pentru
            operațiile AES. Imaginea procesată (criptată/decriptată) este
            stocată într-o bază de date MySQL (BLOB) în C05. Containerul C05
            expune, de asemenea, un API Node.js (Express) pentru a permite
            descărcarea imaginii și pentru a afișa valori SNMP stocate în
            MongoDB. Notificarea către frontend despre finalizarea procesării și
            disponibilitatea imaginii se face prin WebSockets din C03 care
            trimite notificarea pe un topic special către C01, direcționând
            utilizatorul către URL-ul de descărcare din C05.
          </Typography>
          <Typography variant="body1" paragraph textAlign={"justify"}>
            Acest proiect este dezvoltat ca proiect final pentru cursurile de
            Web & Cloud Security și Distributed & Parallel System Security.
          </Typography>

          <Divider sx={{ my: 3 }} />

          <Typography variant="h5" component="h2" gutterBottom>
            Arhitectură și Tehnologii Utilizate
          </Typography>
          <List>
            <ListItem>
              <ListItemIcon>
                <FaReact color="#61DAFB" />
              </ListItemIcon>
              <ListItemText
                primary="Frontend (Container C01)"
                secondary="React, Material UI, React Router, Vite"
              />
            </ListItem>
            <ListItem>
              <ListItemIcon>
                <FaJava color="#007396" />
              </ListItemIcon>
              <ListItemText
                primary="Backend API (Container C01)"
                secondary="Javalin (Java), RESTful APIs"
              />
            </ListItem>
            <ListItem>
              <ListItemIcon>
                <SiRabbitmq color="#FF6600" />
              </ListItemIcon>
              <ListItemText
                primary="Broker de Mesaje (Container C02)"
                secondary="RabbitMQ"
              />
            </ListItem>
            <ListItem>
              <ListItemIcon>
                <FaJava color="#007396" />
              </ListItemIcon>
              <ListItemText
                primary="Consumator & Orchestrator Procesare (Container C03)"
                secondary="Java (POJO RabbitMQ Consumer, Apache HttpClient)"
              />
            </ListItem>
            <ListItem>
              <ListItemIcon>
                <SiCplusplus color="#00599C" />
              </ListItemIcon>
              <ListItemText
                primary="Procesare Nativă Imagine (Containere C03 & C04)"
                secondary="C++, OpenMPI, OpenMP, OpenSSL (pentru AES)"
              />
            </ListItem>
            <ListItem>
              <ListItemIcon>
                <SiExpress color="#000000" />
              </ListItemIcon>
              <ListItemText
                primary="API Rezultate & Management (Container C05)"
                secondary="Node.js, Express.js, RESTful APIs"
              />
            </ListItem>
            <ListItem>
              <ListItemIcon>
                <Box sx={{ display: "flex", alignItems: "center" }}>
                  <SiMysql color="#4479A1" style={{ marginRight: "8px" }} />
                  <SiMongodb color="#47A248" />
                </Box>
              </ListItemIcon>
              <ListItemText
                primary="Baze de Date (Container C05)"
                secondary="MySQL (pentru imagini), MongoDB (pentru SNMP)"
              />
            </ListItem>
            <ListItem>
              <ListItemIcon>
                <FaDocker color="#2496ED" />
              </ListItemIcon>
              <ListItemText
                primary="Containerizare"
                secondary="Docker, Docker Compose"
              />
            </ListItem>
            <ListItem>
              <ListItemIcon>
                <FaLinux color="#FCC624" />
              </ListItemIcon>
              <ListItemText
                primary="Sistem de Operare Container"
                secondary="Ubuntu 24.04 LTS (din imaginea Docker critoma/amd64_u24_noble_ism_security)"
              />
            </ListItem>
          </List>
        </Paper>

        <Paper elevation={3} sx={{ p: 4, mt: 4 }}>
          <Typography variant="h5" component="h2" gutterBottom>
            Autor
          </Typography>
          <Box sx={{ textAlign: "center", mb: 2 }}>
            <Avatar
              sx={{
                width: 100,
                height: 100,
                margin: "0 auto",
                mb: 2,
              }}
            >
              <img
                src="zeekliviu.jpg"
                alt="autor"
                style={{ borderRadius: "50%" }}
              />
            </Avatar>
            <Typography variant="h6">{author.name}</Typography>
            <Typography
              variant="subtitle1"
              color="text.secondary"
              sx={{ mb: 2 }}
            >
              {author.role}
            </Typography>

            <Stack
              direction="row"
              spacing={2}
              justifyContent="center"
              sx={{ mb: 2 }}
            >
              <IconButton
                component={Link}
                href={`mailto:${author.email}`}
                target="_blank"
                aria-label="Email"
              >
                <EmailIcon />
              </IconButton>
              <IconButton
                component={Link}
                href={author.linkedin}
                target="_blank"
                aria-label="LinkedIn"
              >
                <LinkedInIcon />
              </IconButton>
              <IconButton
                component={Link}
                href={author.github}
                target="_blank"
                aria-label="GitHub"
              >
                <GitHubIcon />
              </IconButton>
              <IconButton
                component={Link}
                href={`https://discordapp.com/users/${author.discord}`}
                target="_blank"
                aria-label="Discord"
              >
                <FaDiscord style={{ fontSize: "1.2rem" }} />
              </IconButton>
            </Stack>

            <Divider sx={{ my: 2 }} />

            <Typography variant="body2">{author.description}</Typography>
          </Box>
        </Paper>
      </Box>
    </Container>
  );
};

export default About;

import * as docker from "@pulumi/docker";
import * as pulumi from "@pulumi/pulumi";
import * as path from "node:path";

const config = new pulumi.Config();
const repoRoot = path.resolve(__dirname, "../../..");

const controlPlanePort = Number(config.get("controlPlanePort") ?? 8080);
const generatorPort = Number(config.get("generatorPort") ?? 8081);
const decoderPort = Number(config.get("decoderPort") ?? 8082);
const networkName = config.get("networkName") ?? "back-pressure-local";

const serviceNames = {
  controlPlane: "control-plane",
  generator: "workload-generator",
  decoder: "decoder-worker",
} as const;

const network = new docker.Network("back-pressure-network", {
  name: networkName,
});

function serviceImage(name: string, dockerfileRelativePath: string) {
  return new docker.Image(`${name}-image`, {
    imageName: `docker.io/kynetics/${name}:phase1b`,
    skipPush: true,
    build: {
      context: repoRoot,
      dockerfile: path.join(repoRoot, dockerfileRelativePath),
    },
  });
}

const controlPlaneImage = serviceImage(serviceNames.controlPlane, "services/control-plane/Dockerfile");
const generatorImage = serviceImage(serviceNames.generator, "services/workload-generator/Dockerfile");
const decoderImage = serviceImage(serviceNames.decoder, "services/decoder-worker/Dockerfile");

const decoderContainer = new docker.Container("decoder-worker", {
  name: "back-pressure-decoder-worker",
  image: decoderImage.repoDigest,
  restart: "unless-stopped",
  networksAdvanced: [{ name: network.name, aliases: [serviceNames.decoder] }],
  ports: [{ internal: 8082, external: decoderPort }],
});

const generatorContainer = new docker.Container("workload-generator", {
  name: "back-pressure-workload-generator",
  image: generatorImage.repoDigest,
  restart: "unless-stopped",
  networksAdvanced: [{ name: network.name, aliases: [serviceNames.generator] }],
  ports: [{ internal: 8081, external: generatorPort }],
}, { dependsOn: [decoderContainer] });

const controlPlaneContainer = new docker.Container("control-plane", {
  name: "back-pressure-control-plane",
  image: controlPlaneImage.repoDigest,
  restart: "unless-stopped",
  networksAdvanced: [{ name: network.name, aliases: [serviceNames.controlPlane] }],
  envs: [
    `BACKPRESSURE_GENERATOR_BASE_URL=http://${serviceNames.generator}:8081`,
    `BACKPRESSURE_DECODER_BASE_URL=http://${serviceNames.decoder}:8082`,
  ],
  ports: [{ internal: 8080, external: controlPlanePort }],
}, { dependsOn: [generatorContainer, decoderContainer] });

export const phase = "phase-1b";
export const plannedServices = Object.values(serviceNames);
export const plannedPorts = {
  controlPlane: controlPlanePort,
  generator: generatorPort,
  decoderWorker: decoderPort,
};
export const serviceUrls = {
  controlPlane: pulumi.interpolate`http://localhost:${controlPlanePort}`,
  generator: pulumi.interpolate`http://localhost:${generatorPort}`,
  decoderWorker: pulumi.interpolate`http://localhost:${decoderPort}`,
};
export const containerNames = {
  controlPlane: controlPlaneContainer.name,
  generator: generatorContainer.name,
  decoderWorker: decoderContainer.name,
};

const path = require('path');
const webpack = require('webpack');

var config = {
  // Logsidian: Use production mode when NODE_ENV is set (enables tree shaking)
  mode: process.env.NODE_ENV === 'production' ? 'production' : 'development',
  externals: {
    'react': 'React',
    'react-dom': 'ReactDOM',
  },
  module: {
    rules: [
      {
        // docs: https://webpack.js.org/configuration/module/#resolvefullyspecified
        test: /\.m?js/,
        resolve: {
          fullySpecified: false,
        }
      }
    ]
  },
  plugins: [
    // fix "process is not defined" error:
    new webpack.ProvidePlugin({
      process: 'process/browser',
    }),
  ],
};

var AppConfig = Object.assign({}, config, {
  name: "app",
  entry: {
    "db-worker" : "./target/db-worker.js",
    "inference-worker" : "./target/inference-worker.js"
  },

  output: {
    path: path.resolve(__dirname, 'static/js'),
    filename: '[name]-bundle.js',
    clean: false,
    chunkLoading: false,
  },
});

var MobileConfig = Object.assign({}, config, {
  name: "mobile",
  entry: {
    "db-worker" : "./target/db-worker.js",
  },

  output: {
    path: path.resolve(__dirname, 'static/mobile/js'),
    filename: '[name]-bundle.js',
    clean: false,
    chunkLoading: false,
  },
});

module.exports = [
  AppConfig, MobileConfig,
];
